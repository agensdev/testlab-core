package no.agens.testlab.services.screens

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.profile.ExampleProfile
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrScreensTest {
    private val tmp = Files.createTempDirectory("tl-prscreens").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(mapOf("github.token" to "ghp_prscreens_0123456789"))).also {
        it.githubHttp = gh.client
        ExampleProfile.install(it, ExampleProfile.PROFILE)
    }
    private val repo = "/repos/example-org/shop-android"
    private val paparazzi = "feature/cart/src/test/snapshots/images/com.example.shop.cart_CartScreenSnapshotTest_empty.png"
    private val ios = "ShopTests/__Snapshots__/CheckoutTests/testPay.1.png"
    private val downloads = mutableListOf<String>()

    private fun png(w: Int, h: Int, c: Color): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = c; fillRect(0, 0, w, h); dispose() } }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    init {
        gh.json("GET $repo/pulls/7", """{"number":7,"title":"Cart: empty state","body":"","user":{"login":"dev"},"state":"open","draft":false,"merged":false,
            "head":{"ref":"feature/cart","sha":"head1234567890"},"base":{"ref":"main","sha":"main555"},"created_at":"","updated_at":"","additions":1,"deletions":1,"changed_files":4,"html_url":"u"}""")
        gh.json("GET $repo/pulls/7/files", """[
            {"filename":"feature/cart/src/main/kotlin/CartScreen.kt","status":"modified","additions":3,"deletions":1},
            {"filename":"$paparazzi","status":"modified","additions":0,"deletions":0},
            {"filename":"$ios","status":"added","additions":0,"deletions":0},
            {"filename":"app/src/main/res/drawable/logo.png","status":"modified","additions":0,"deletions":0}
        ]""")
        gh.json("GET $repo/compare/main555...head1234567890", """{"ahead_by":2,"behind_by":0,"merge_base_commit":{"sha":"base0987654321"},"files":[]}""")
        gh.on("GET $repo/contents/") { req ->
            downloads += req.url.encodedPath + "?" + req.url.encodedQuery
            val ref = req.url.parameters["ref"]
            respond(png(40, 80, if (ref == "base0987654321") Color.RED else Color.GREEN), HttpStatusCode.OK)
        }
    }

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    @Test
    fun beforeAndAfterOfTheChangedScreenshotTests() = runBlocking {
        val r = rt.prScreens.forPr(7)
        assertEquals("base0987654321" to "head1234567890", r.base to r.head)
        assertEquals(listOf(paparazzi, ios), r.screens.map { it.path }, "only screenshot-test images, not app resources")
        val (cart, pay) = r.screens
        assertEquals("CartScreenSnapshotTest · empty", cart.name)
        assertNotNull(cart.before); assertNotNull(cart.after)
        val pair = ImageIO.read(cart.pair)
        assertTrue(pair.width > pair.height, "side by side")
        assertEquals(PrScreens.Diff(1.0, 0.0, 1.0), cart.diff, "red before, green after: every pixel")
        assertNull(pay.diff)
        assertNull(cart.resized, "same size: measured, not resized")
        assertEquals("added", pay.status)
        assertNull(pay.before, "a new image has no before")
        assertTrue(downloads.any { "ref=base0987654321" in it } && downloads.any { "ref=head1234567890" in it }, downloads.toString())
        assertFalse(downloads.any { "logo" in it })

        val n = downloads.size
        rt.prScreens.forPr(7)
        assertEquals(n, downloads.size, "the same head is not downloaded again")
        assertEquals(1, rt.prScreens.forPr(7, filter = "checkout").screens.size)
    }

    @Test
    fun aOnePixelShiftIsNotAChangeButNewTextIs() {
        fun img(name: String, draw: java.awt.Graphics2D.() -> Unit) = File(tmp, "$name.png").also { f ->
            val i = BufferedImage(200, 400, BufferedImage.TYPE_INT_RGB)
            i.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 200, 400); color = Color.BLACK; draw(); dispose() }
            ImageIO.write(i, "png", f)
        }
        val base = img("a") { fillRect(20, 20, 100, 30) }
        val shifted = img("b") { fillRect(21, 21, 100, 30) }
        val changed = img("c") { fillRect(20, 20, 100, 30); fillRect(20, 300, 150, 20) }
        assertEquals("identical (allowing a one-pixel shift)", PrScreens.diff(base, shifted).toString())
        val d = PrScreens.diff(base, changed)!!
        assertTrue(d.share > 0.03 && d.top in 0.74..0.76 && d.bottom in 0.79..0.81, d.toString())
    }

    @Test
    fun wideImagesAreStacked() {
        fun img(name: String, w: Int, h: Int) = File(tmp, "$name.png").also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }
        val card = PrScreens.pair(img("cb", 1050, 220), img("ca", 1050, 208), File(tmp, "card.png"))!!.let(ImageIO::read)
        assertTrue(card.height > card.width / 3 && card.width < 1100, "stacked: ${card.width}x${card.height}")
        val screen = PrScreens.pair(img("sb", 400, 860), img("sa", 400, 860), File(tmp, "screen.png"))!!.let(ImageIO::read)
        assertTrue(screen.width > 800 && screen.height < 1000, "side by side, one row: ${screen.width}x${screen.height}")
    }

    @Test
    fun patternsAndNames() {
        val d = PrScreens.DEFAULTS
        assertTrue(PrScreens.isSnapshot("snapshots/images/a.png", d), "at the repo root too")
        assertTrue(PrScreens.isSnapshot("app/src/test/screenshots/home.png", d))
        assertFalse(PrScreens.isSnapshot("app/src/test/snapshots/images/readme.md", d))
        assertFalse(PrScreens.isSnapshot("app/src/main/res/mipmap/icon.png", d))
        assertTrue(PrScreens.isSnapshot("ui/goldens/home.png", listOf("**/goldens/**")))
        assertEquals("TrekLobbyScreenSnapshotTest · extraSuccessDark", PrScreens.nameOf("x/no.example.lottery.lobby_TrekLobbyScreenSnapshotTest_extraSuccessDark.png"))
        assertEquals("testPay.1", PrScreens.nameOf("A/__Snapshots__/T/testPay.1.png"))
    }
}
