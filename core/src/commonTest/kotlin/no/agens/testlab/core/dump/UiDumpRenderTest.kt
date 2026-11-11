package no.agens.testlab.core.dump

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The upstream ui_dump.rs unit tests, ported, plus the edge cases the port must keep. */
class UiDumpRenderTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test
    fun rendersVerbLedLayoutForLoginScreen() {
        val dump = parse(
            """{"root":{"cls":"android.widget.LinearLayout","rid":"","text":"","desc":"","flags":"","bounds":[0,0,1080,1920],"children":[
              {"cls":"android.widget.EditText","rid":"com.foo:id/email","text":"","desc":"Email","flags":"ce","bounds":[0,500,1080,580],"children":[]},
              {"cls":"android.widget.EditText","rid":"com.foo:id/password","text":"","desc":"Password","flags":"cep","bounds":[0,600,1080,680],"children":[]},
              {"cls":"android.widget.Button","rid":"com.foo:id/continue","text":"Continue","desc":"","flags":"ce","bounds":[0,820,1080,900],"children":[]}]}}""",
        )
        val lines = UiDumpRender.renderTable(dump).trimEnd().lines()
        assertEquals(
            listOf(
                "fill  EditText  \"Email\"  #email  540,540",
                "fill  EditText  \"Password\"  #password  540,640  [password]",
                "tap   Button    \"Continue\"  #continue  540,860",
            ),
            lines,
        )
    }

    @Test
    fun anonymousClickableImageViewRendersWithIdAsLabel() {
        val dump = parse(
            """{"root":{"cls":"android.widget.LinearLayout","bounds":[0,0,1080,1920],"children":[
              {"cls":"android.widget.ImageView","rid":"com.foo:id/back_btn","flags":"ce","bounds":[40,200,160,320],"children":[]},
              {"cls":"android.widget.ImageView","rid":"com.foo:id/decorative","flags":"e","bounds":[0,400,100,500],"children":[]}]}}""",
        )
        assertEquals("tap  ImageView  #back_btn  100,260\n", UiDumpRender.renderTable(dump))
    }

    @Test
    fun dropsOffscreenAndZeroAreaNodes() {
        val dump = parse(
            """{"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"children":[
              {"cls":"android.widget.Button","rid":"com.foo:id/ok","text":"OK","flags":"ce","bounds":[100,200,300,400],"children":[]},
              {"cls":"android.widget.Button","rid":"com.foo:id/scrolled","text":"Off the top","flags":"ce","bounds":[100,-300,300,-100],"children":[]},
              {"cls":"android.widget.Button","rid":"com.foo:id/zero","text":"Zero size","flags":"ce","bounds":[0,0,0,0],"children":[]}]}}""",
        )
        assertEquals("tap  Button  \"OK\"  #ok  200,300\n", UiDumpRender.renderTable(dump))
    }

    @Test
    fun treeLineFormatAndNullChild() {
        val dump = parse(
            """{"ts":1,"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"children":[
              {"cls":"android.view.View","rid":"no.x:id/fragment_games","desc":"Spill","flags":"cfev","bounds":[300,2850,550,2940]},
              null]}}""",
        )
        assertEquals(
            "FrameLayout  @(540,1200)\n" +
                "  View #fragment_games  desc=\"Spill\"  [click,focusable]  @(425,2895)\n" +
                "  \n",
            UiDumpRender.renderTree(dump),
        )
    }

    @Test
    fun truncationCountsCodePointsAndEscapes() {
        // 61 emoji = 122 UTF-16 units; truncation must count code points like Rust's chars().
        val emoji = "😀".repeat(61)
        val q = UiDumpRender.truncateQuoted(emoji, 60)
        assertEquals("\"" + "😀".repeat(60) + "…\"", q)
        assertEquals("\"a\\\"b↵c d\"", UiDumpRender.truncateQuoted("a\"b\nc\td​", 80))
    }

    @Test
    fun fullDumpWindowsEnvelope() {
        val dump = parse("""{"windows":[{"title":"a","root":{"cls":"x.A","text":"one","bounds":[0,0,10,10]}},{"root":{"cls":"x.B","text":"two","bounds":[0,0,10,10]}}]}""")
        val tree = UiDumpRender.renderTree(dump)
        assertTrue(tree.contains("A  \"one\"") && tree.contains("B  \"two\""), tree)
    }
}
