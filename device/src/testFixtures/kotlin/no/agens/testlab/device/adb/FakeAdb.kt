package no.agens.testlab.device.adb

import java.io.File
import java.util.Collections

/**
 * adb without a process: [reply] gets the arguments (`-s SERIAL` included) and returns stdout;
 * every call is recorded. Return [FAIL] + text for a failing exit code.
 */
class FakeAdb(private val reply: (List<String>) -> String = { "" }) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val cli = AdbCli(File("fake-adb"), exec = { args ->
        calls += args.joinToString(" ")
        val out = reply(args)
        if (out.startsWith(FAIL)) AdbCli.Result(1, "", out.removePrefix(FAIL)) else AdbCli.Result(0, out, "")
    })

    companion object {
        const val FAIL = "\u0000fail:"
    }
}
