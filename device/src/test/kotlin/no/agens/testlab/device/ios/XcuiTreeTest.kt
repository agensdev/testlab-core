package no.agens.testlab.device.ios

import no.agens.testlab.core.dump.UiDumpRender
import no.agens.testlab.core.sel.NodeQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XcuiTreeTest {
    private val xml = javaClass.getResource("/ios/login.xml")!!.readText()
    private val dump = XcuiTree.toDump(xml, 3.0, "com.example.shop")

    @Test
    fun anIosScreenReadsLikeAnAndroidOne() {
        val table = UiDumpRender.renderTable(dump)
        assertTrue(Regex("""fill\s+EditText\s+"you@example.com"\s+#email\s+603,558""").containsMatchIn(table), table)
        assertTrue(Regex("""tap\s+Switch\s+"Remember me"\s+#remember""").containsMatchIn(table) && "checked" in table, table)
        assertTrue("password" in table, "the secure field is a password field:\n$table")
        assertFalse("Hidden" in table, "invisible nodes are dropped")
        val tree = UiDumpRender.renderTree(dump)
        assertTrue("Button #BackButton  \"Shop\"" in tree, tree)
        assertTrue(Regex("""Button\s+"Home"\s+\[click,selected]""").containsMatchIn(tree), "the selected tab:\n$tree")
        assertFalse(tree.lines().any { it.trimStart().startsWith("View") && it.length - it.trimStart().length > 12 }, "one-child wrappers are skipped:\n$tree")
    }

    @Test
    fun selectorsAndFillFindTheFields() {
        assertEquals("id=email", NodeQuery.fillSelector(dump, "E-mail"))
        assertEquals("id=password", NodeQuery.fillSelector(dump, "Password"))
        val hit = NodeQuery.tapHits(dump, "Cart").single()
        assertEquals(603, hit.bounds.centerX, "pixels: 134..268 pt × 3")
        val withKeyboard = xml.replace(
            "</XCUIElementTypeWindow>",
            "<XCUIElementTypeKeyboard type=\"XCUIElementTypeKeyboard\" visible=\"true\" x=\"0\" y=\"500\" width=\"402\" height=\"300\"><XCUIElementTypeKey type=\"XCUIElementTypeKey\" label=\"q\" visible=\"true\"/></XCUIElementTypeKeyboard></XCUIElementTypeWindow>",
        )
        val kd = XcuiTree.toDump(withKeyboard, 3.0, "x")
        assertTrue(XcuiTree.keyboardShown(kd))
        assertFalse("\"q\"" in UiDumpRender.renderTree(kd), "the keyboard's keys stay out of the dump")
        assertFalse(XcuiTree.keyboardShown(dump))
    }
}
