package no.agens.testlab.services.service

import no.agens.testlab.device.HostOs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A desktop notification, best effort: macOS through `osascript`, Linux through `notify-send` when
 * it is there; nothing elsewhere or when it fails. With no window, this is how the person at the
 * computer learns that something waits for them (an approval). `TESTLAB_NOTIFY=0` turns it off.
 */
object DesktopNotice {
    fun show(title: String, text: String) {
        if (System.getenv("TESTLAB_NOTIFY") == "0") return
        val cmd = when (HostOs.current()) {
            HostOs.MAC -> listOf("/usr/bin/osascript", "-e", "display notification ${quote(text)} with title ${quote(title)}")
            HostOs.LINUX -> listOf("/usr/bin/notify-send", title, text).takeIf { File(it[0]).canExecute() }
            HostOs.WINDOWS -> null
        } ?: return
        runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** An AppleScript string literal. */
    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
