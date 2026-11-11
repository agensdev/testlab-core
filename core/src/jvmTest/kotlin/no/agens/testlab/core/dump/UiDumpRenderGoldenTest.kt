package no.agens.testlab.core.dump

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Byte-for-byte parity with the upstream handsets CLI (v0.1.38). Each golden trio was captured
 * by scripts/dev/capture-goldens.sh on the dev emulator: the daemon's dump_active JSON and
 * what `hs ui --tree` and `hs ui` printed for that same screen.
 */
class UiDumpRenderGoldenTest {
    private val dir = File("src/jvmTest/resources/goldens/dumps")

    private fun cases(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()

    @Test
    fun atLeastFifteenScreensCaptured() {
        assertTrue(cases().size >= 15, "only ${cases().size} golden screens in $dir")
    }

    @Test
    fun treeMatchesHsUiTree() = compare("tree") { UiDumpRender.renderTree(it) }

    @Test
    fun tableMatchesHsUi() = compare("table") { UiDumpRender.renderTable(it) }

    private fun compare(ext: String, render: (kotlinx.serialization.json.JsonElement) -> String) {
        val failures = mutableListOf<String>()
        for (name in cases()) {
            val json = Json.parseToJsonElement(File(dir, "$name.json").readText())
            val expected = File(dir, "$name.$ext").readText()
            val actual = render(json)
            if (expected != actual) {
                val e = expected.lines()
                val a = actual.lines()
                val i = e.indices.firstOrNull { it >= a.size || e[it] != a[it] } ?: e.size
                failures += "$name.$ext line ${i + 1}:\n  hs:     ${e.getOrNull(i)}\n  kotlin: ${a.getOrNull(i)}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases().size} differ:\n" + failures.joinToString("\n"))
    }
}
