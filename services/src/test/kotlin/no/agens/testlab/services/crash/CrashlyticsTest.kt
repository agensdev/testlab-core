package no.agens.testlab.services.crash

import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.core.profile.problems
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.logs.LogCapture
import no.agens.testlab.services.profile.ExampleProfile
import no.agens.testlab.services.runtime.Emulators
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import no.agens.testlab.services.tools.AndroidCli
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Crashlytics and emulators through a fake `android` that answers like Google's Android CLI. */
class CrashlyticsTest {
    private val tmp = Files.createTempDirectory("tl-crash").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(emptyMap())).also {
        ExampleProfile.install(it, ExampleProfile.PROFILE.copy(crashlytics = Profile.Crashlytics(mapOf(
            "com.example.shop" to "1:1234567890:android:abc123",
            "com.example.shop.debug" to "1:999:android:def456",
        ))))
    }
    private val calls = File(tmp, "calls.txt")
    private val signedIn = File(tmp, "signed-in")

    // The shape `android signals crashes list --format=json` answers with: a group per source, the issues inside.
    private val issues = """
        [{"source":"firebase","appId":"1:1234567890:android:abc123","timeframe":"7d","issues":[
          {"issueId":"firebase/aaa","type":"Fatal","userCount":3,"eventCount":9,"title":"CartRepository.total: NullPointerException - null cart"},
          {"issueId":"firebase/bbb","type":"Fatal","userCount":120,"eventCount":800,"title":"CheckoutViewModel.pay: IllegalStateException - card declined"},
          {"issueId":"firebase/ccc","type":"ANR","userCount":40,"eventCount":60,"title":"MessageQueue.nativePollOnce: Input dispatching timed out"}
        ]}]
    """.trimIndent()

    private val issuesFile = File(tmp, "issues.json").apply { writeText(issues) }
    private val script = File(tmp, "android").apply {
        writeText(
            """
            #!/bin/sh
            echo "$*" >> '${calls.path}'
            case "$*" in
              *"info version"*) echo "1.1.16717390" ;;
              *"auth status"*) if [ -f '${signedIn.path}' ]; then echo "Logged in as tester@example.com"; else echo "Not logged in. Run 'android auth login' to log in."; fi ;;
              *"signals"*) if [ ! -f '${signedIn.path}' ]; then echo "Error: Not logged in. Please run 'android auth login' first." >&2; exit 1; fi
                case "$*" in
                  *" list "*) cat '${issuesFile.path}' ;;
                  *describe*) printf '%s\n' '{"issue":{"id":"firebase/bbb"},"stack":"java.lang.IllegalStateException: card declined\n at com.example.shop.checkout.CheckoutViewModel.pay(CheckoutViewModel.kt:42)"}' ;;
                  *context*) echo '{"projects":[{"projectId":"example-shop","apps":[{"appId":"1:1234567890:android:abc123","packageName":"com.example.shop"}]}]}' ;;
                esac ;;
              *"emulator list"*) printf 'AVD ID                   AVD Name                      API Level      Status         Serial\nsmall_phone              small_phone                   android-36     Online         emulator-5554\nbig_phone                big_phone                     android-36     Offline        \n' ;;
              *"emulator create --list-profiles"*) printf 'large_desktop\nmedium_phone\nsmall_phone\n' ;;
              *"emulator create"*) echo "Successfully created device 'medium_phone' with profile 'medium_phone'." ;;
              *) echo "Error: unknown command $*" >&2; exit 2 ;;
            esac
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }
    private val cli = AndroidCli(no.agens.testlab.services.github.GitHub.httpClient(), locate = { script })
    private val crash = Crashlytics(rt) { cli }

    private val logcat = """
        10-29 12:00:01.000  4321  4321 D Shop    : tap pay
        10-29 12:00:02.000  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
        10-29 12:00:02.000  4321  4321 E AndroidRuntime: Process: com.example.shop, PID: 4321
        10-29 12:00:02.000  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: card declined
        10-29 12:00:02.000  4321  4321 I Shop    : unrelated line
        10-29 12:00:02.000  4321  4321 E AndroidRuntime: 	at com.example.shop.checkout.CheckoutViewModel.pay(CheckoutViewModel.kt:42)
        10-29 12:00:02.000  4321  4321 E AndroidRuntime: 	at android.view.View.performClick(View.java:7000)
        10-29 12:00:09.000  9999  9999 E AndroidRuntime: FATAL EXCEPTION: main
        10-29 12:00:09.000  9999  9999 E AndroidRuntime: Process: com.genymobile.scrcpy, PID: 9999
        10-29 12:00:09.000  9999  9999 E AndroidRuntime: java.io.IOException: Broken pipe
    """.trimIndent()

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    @Test
    fun signedOutSaysHowToSignIn() = runBlocking {
        assertNull(cli.account())
        assertEquals("1.1.16717390", cli.version())
        val e = assertFailsWith<IllegalStateException> { crash.issues(null) }
        assertTrue("testlab android-cli login" in e.message!!, e.message)
        assertEquals(Crashlytics.NOT_SIGNED_IN, crash.status())
    }

    @Test
    fun listsAndMatchesTheProductionApp() = runBlocking {
        signedIn.writeText("")
        assertEquals("tester@example.com", cli.account())
        val (pkg, tf, all) = crash.issues(null, appVersion = "2.4.0")
        assertEquals("com.example.shop" to "7d", pkg to tf)
        assertEquals(listOf("firebase/aaa", "firebase/bbb", "firebase/ccc"), all.map { it.id })
        val call = calls.readLines().last { "signals" in it }
        assertTrue("--firebase-app=1:1234567890:android:abc123" in call && "--app-version=2.4.0" in call && "--format=json" in call && call.startsWith("--no-metrics"), call)

        // The rig's crash (not the screen recorder's) finds the checkout issue, first and alone.
        val traces = LogCapture.crashTraces(logcat)
        assertEquals(1, traces.size)
        assertTrue("unrelated line" !in traces[0] && "CheckoutViewModel.pay" in traces[0], traces[0])
        val (_, _, hits) = crash.issues(null, match = traces[0])
        assertEquals("firebase/bbb", hits.first().id)
        assertTrue(hits.none { it.id == "firebase/ccc" }, "a framework frame alone matches nothing")
        val (_, _, weak) = crash.issues(null, match = "java.lang.NullPointerException: boom")
        assertTrue(weak.isEmpty(), "sharing only a common exception is no match: $weak")

        crash.issues("com.example.shop.debug")
        assertTrue("--firebase-app=1:999:android:def456" in calls.readLines().last())
        assertFailsWith<IllegalArgumentException> { crash.issues("com.other") }
        assertTrue("card declined" in crash.issue("firebase/bbb", null, include = "stack").toString())
        assertTrue("example-shop" in crash.apps().toString())
    }

    @Test
    fun crashTermsWeighTheExceptionAndTheAppsOwnFrames() {
        val t = Crashlytics.terms("java.lang.IllegalStateException: card declined\n\tat com.example.shop.checkout.CheckoutViewModel.pay(X.kt:1)\n\tat android.view.View.performClick(View.java:1)")
        assertEquals(1, t["illegalstateexception"], "a common exception says little")
        assertEquals(3, Crashlytics.terms("Caused by: com.example.shop.CardDeclinedException: no")["carddeclinedexception"], "the app's own says a lot")
        assertEquals(2, t["checkoutviewmodel.pay"])
        assertTrue(t.keys.none { "view" == it || "performclick" in it }, t.toString())
        assertTrue("declined" in t)
    }

    @Test
    fun emulatorsThroughTheCli() {
        val emus = Emulators(rt) { cli }
        val list = emus.avds()
        assertEquals(listOf(Emulators.Avd("small_phone", "android-36", true, "emulator-5554"), Emulators.Avd("big_phone", "android-36", false, null)), list)
        assertEquals(listOf("large_desktop", "medium_phone", "small_phone"), emus.profiles())
        assertEquals("medium_phone", emus.create("medium_phone"))
        assertFailsWith<IllegalArgumentException> { emus.create("Bad Profile; rm") }
        assertEquals("emulator-5554", emus.start("small_phone"), "a running one is not started again")
        assertFailsWith<IllegalArgumentException> { emus.start("nope") }
        assertEquals(listOf(Emulators.Avd("a"), Emulators.Avd("b")), Emulators.parseList("a\nb\n"), "the plain list (no --long)")
    }

    @Test
    fun theProfileChecksItsCrashlyticsApps() {
        val p = ExampleProfile.PROFILE.copy(crashlytics = Profile.Crashlytics(mapOf("com.example.shop" to "abc", "not a package" to "1:1:android:ab"), timeframe = "1y"))
        val probs = p.problems()
        assertTrue(probs.any { "'abc' is not a Firebase Android app id" in it }, probs.toString())
        assertTrue(probs.any { "'not a package' is not a package name" in it }, probs.toString())
        assertTrue(probs.any { "crashlytics.timeframe" in it }, probs.toString())
        assertTrue(ExampleProfile.PROFILE.copy(crashlytics = Profile.Crashlytics(mapOf("com.example.shop" to "1:1234567890:android:abc123"))).problems().none { "crashlytics" in it })
    }
}
