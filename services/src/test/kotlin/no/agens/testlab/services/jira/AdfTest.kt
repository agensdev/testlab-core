package no.agens.testlab.services.jira

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdfTest {
    private val sample = """
        ## devtest 02.10.2026

        **Testet av:** Test⏎
        **Bygg:** 5.12.0 (51200)

        ### Testet

        - ✅ The cart viser *items*
        - ❌ Se `CartScreen` og [PR](https://github.com/x/pull/1)
          1. nested first
          2. nested second

        > NB: test på INT

        ```kotlin
        val x = 1
        ```

        | Steg | Resultat |
        | --- | --- |
        | Åpne | OK |

        ---

        🤖 Written by Claude, approved by Test
    """.trimIndent().replace("⏎", "  ") // a Markdown hard break is two trailing spaces

    @Test
    fun markdownRoundTripsThroughAdf() {
        val adf = Adf.fromMarkdown(sample)
        val back = Adf.toMarkdown(adf)
        assertEquals(sample, back)
    }

    @Test
    fun adfIsValidShape() {
        val adf = Adf.fromMarkdown("Hei **du**\nlinje to  \nhard\n\n![bilde](01-start.jpg)")
        val s = adf.toString()
        assertTrue("\"version\":1" in s)
        assertTrue("{\"type\":\"hardBreak\"}" in s, s)
        assertTrue("\"text\":\"Hei \"" in s && "\"text\":\" linje to\"" in s, s)
        assertTrue("\"href\":\"01-start.jpg\"" in s, "images become links: $s")
        assertTrue("\"text\":\"\"" !in s, "ADF rejects empty text nodes")
    }

    @Test
    fun jiraOnlyNodesBecomeText() {
        val doc = Json.parseToJsonElement(
            """{"type":"doc","version":1,"content":[
              {"type":"paragraph","content":[{"type":"mention","attrs":{"id":"1","text":"@Kari"}},{"type":"text","text":" se "},{"type":"inlineCard","attrs":{"url":"https://x.no"}},{"type":"status","attrs":{"text":"KLAR"}}]},
              {"type":"panel","attrs":{"panelType":"warning"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Pass på"}]}]},
              {"type":"mediaSingle","content":[{"type":"media","attrs":{"id":"abc","alt":"skjerm.png"}}]}
            ]}""",
        )
        assertEquals("@Kari se <https://x.no>[KLAR]\n\n> **WARNING**\n>\n> Pass på\n\n[attachment: skjerm.png]", Adf.toMarkdown(doc).replace("> \n", ">\n"))
    }
}
