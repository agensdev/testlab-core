package no.agens.testlab.services.report

import no.agens.testlab.services.files.readTextCloud
import no.agens.testlab.services.files.readBytesCloud
import no.agens.testlab.services.files.readLinesCloud
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.util.Base64

/** One chapter or note on a recording, [t] seconds from its first frame. */
data class TimelineEvent(val t: Double, val kind: String, val title: String, val text: String)

data class PlayerVideo(val file: String, val events: List<TimelineEvent>, val caption: String)

/**
 * The report page: self-contained (styles and script inline, no network), readable in light and
 * dark mode, working from `file://` and as a Jira attachment. Markdown comes from an agent, so raw
 * HTML in it is escaped; the player and galleries are generated here.
 */
object ReportHtml {
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()

    fun markdown(md: String): String = groupShots(renderer.render(parser.parse(md)))

    private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val IMG_RUN = Regex("(?:<p>\\s*(?:<img\\b[^>]*>\\s*(?:<br\\s*/?>\\s*)?\\s*)+</p>\\s*)+", RegexOption.IGNORE_CASE)
    private val ALT = Regex("alt=\"([^\"]*)\"")

    /** Screenshots on consecutive lines (one paragraph, or adjacent ones) sit side by side, as in the lab. */
    fun groupShots(html: String): String = IMG_RUN.replace(html) { m ->
        val imgs = IMG.findAll(m.value).map { it.value }.toList()
        if (imgs.size < 2) m.value else "<div class=\"shots\">" + imgs.joinToString("") { i ->
            val cap = ALT.find(i)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            "<figure>$i${cap?.let { "<figcaption>$it</figcaption>" } ?: ""}</figure>"
        } + "</div>\n"
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun jsonString(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '<' -> append("\\u003c") // never "</script>" inside the inline JSON
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun timelineJson(events: List<TimelineEvent>): String = events.joinToString(",", "[", "]") { e ->
        "{\"t\":${"%.2f".format(java.util.Locale.ROOT, e.t)},\"kind\":${jsonString(e.kind)},\"title\":${jsonString(e.title)},\"text\":${jsonString(e.text)}}"
    }

    fun player(v: PlayerVideo, idx: Int): String = """
        <div class="player" id="p$idx">
          <div class="vp"><video controls preload="metadata" playsinline src="${esc(v.file)}"></video><div class="vp-cap"></div></div>
          <div class="vp-chaps"></div>
          <script type="application/json" class="vp-timeline">${timelineJson(v.events)}</script>
        </div>
        <p class="meta">${esc(v.caption)} · <a href="${esc(v.file)}">${esc(v.file)}</a></p>
    """.trimIndent()

    fun page(title: String, subtitle: String, bodyHtml: String): String = """
        <!doctype html>
        <html lang="no"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${esc(title)}</title><style>$CSS</style></head>
        <body><main><h1>${esc(title)}</h1><p class="meta">${esc(subtitle)}</p>
        $bodyHtml
        </main><script>$PLAYER_JS</script></body></html>
    """.trimIndent() + "\n"

    /** The same page with every local image inlined, for a single-file attachment (the video stays a separate file). */
    fun inlineImages(html: String, dir: File): String = Regex("src=\"([^\"/:]+\\.(jpe?g|png|webp))\"").replace(html) { m ->
        val f = File(dir, m.groupValues[1])
        if (!f.isFile) m.value else {
            val mime = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            "src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(f.readBytesCloud())}\""
        }
    }

    val CSS = """
        :root{--bg:#fbfbfd;--panel:#ffffff;--fg:#1d2228;--mut:#5f6b7a;--line:#d9dee5;--accent:#1f7a3a;--link:#0b5cad;--code:#f2f4f7;--cap:#eef3fa}
        @media (prefers-color-scheme: dark){:root{--bg:#0e1116;--panel:#161b22;--fg:#cdd9e5;--mut:#8b949e;--line:#2a313c;--accent:#3fb950;--link:#58a6ff;--code:#0b0e13;--cap:#13202e}}
        *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--fg);font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;line-height:1.6}
        main{max-width:960px;margin:0 auto;padding:24px 16px 64px}
        h1{font-size:26px;margin:8px 0 2px} h2{font-size:19px;margin:28px 0 8px;border-bottom:1px solid var(--line);padding-bottom:6px} h3{font-size:16px;margin:18px 0 6px}
        .meta{color:var(--mut);font-size:13px;margin:2px 0 14px} a{color:var(--link)}
        code{background:var(--code);padding:1px 6px;border-radius:4px;font-size:13px} pre{background:var(--code);border:1px solid var(--line);border-radius:8px;padding:12px 14px;overflow:auto} pre code{background:none;padding:0}
        img{max-width:100%;border:1px solid var(--line);border-radius:8px;margin:8px 0;display:block}
        .shots{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-start;margin:12px 0}
        .shots figure{margin:0;display:flex;flex-direction:column;align-items:center}
        .shots img{height:440px;width:auto;max-width:100%;margin:0}
        .shots figcaption{color:var(--mut);font-size:12px;text-align:center;margin-top:4px;max-width:260px}
        @media(max-width:640px){.shots img{height:auto;width:100%}}
        table{border-collapse:collapse;width:100%;font-size:14px;margin:8px 0;display:block;overflow-x:auto} th,td{border:1px solid var(--line);padding:6px 9px;text-align:left}
        blockquote{border-left:3px solid var(--link);margin:10px 0;padding:6px 12px;background:var(--cap);color:var(--mut)}
        .player{margin:10px 0;display:flex;flex-wrap:wrap;gap:14px;align-items:flex-start}
        .vp{flex:0 0 auto;width:min(440px,100%)} .vp video{width:100%;border-radius:8px;background:#000;display:block}
        .vp-cap{min-height:1.5em;margin-top:6px;font-size:14px;line-height:1.4;background:var(--cap);border:1px solid var(--line);border-radius:6px;padding:7px 11px}
        .vp-cap:empty{display:none}
        .vp-chaps{display:flex;flex-direction:column;gap:4px;flex:1 1 240px;min-width:220px;max-width:520px;max-height:80vh;overflow:auto}
        .vp-chap{display:flex;gap:8px;align-items:baseline;text-align:left;background:var(--panel);border:1px solid var(--line);color:var(--fg);border-radius:6px;padding:6px 10px;cursor:pointer;font-size:13px;width:100%;font-family:inherit}
        .vp-chap:hover{border-color:var(--link)} .vp-chap.on{border-color:var(--accent)} .vp-chap .t{color:var(--mut);font-variant-numeric:tabular-nums;min-width:42px}
    """.trimIndent()

    /** Chapters from the inline timeline (no fetch: pages open from file://), notes in the caption strip. */
    val PLAYER_JS = """
        (function(){
          function fmt(s){s=Math.max(0,Math.floor(s));return Math.floor(s/60)+":"+("0"+(s%60)).slice(-2);}
          document.querySelectorAll(".player").forEach(function(el){
            var v=el.querySelector("video"),cap=el.querySelector(".vp-cap"),list=el.querySelector(".vp-chaps"),ev=[];
            try{ev=JSON.parse(el.querySelector(".vp-timeline").textContent)||[];}catch(e){}
            ev.sort(function(a,b){return a.t-b.t;});
            var items=[];
            ev.forEach(function(e,i){ if(e.kind!=="chapter")return;
              var b=document.createElement("button");b.type="button";b.className="vp-chap";
              var t=document.createElement("span");t.className="t";t.textContent=fmt(e.t);
              var l=document.createElement("span");l.textContent=e.title||e.text||("Kapittel "+(items.length+1));
              b.appendChild(t);b.appendChild(l);
              b.addEventListener("click",function(){v.currentTime=e.t;v.play().catch(function(){});});
              list.appendChild(b);items.push({t:e.t,el:b});});
            if(!items.length)list.remove();
            v.addEventListener("timeupdate",function(){
              var ct=v.currentTime,txt="";
              for(var i=0;i<ev.length;i++){ if(ev[i].t<=ct+0.2) txt=ev[i].text||ev[i].title||""; else break; }
              cap.textContent=txt;
              var a=-1; for(var j=0;j<items.length;j++) if(items[j].t<=ct+0.3) a=j;
              for(var k=0;k<items.length;k++) items[k].el.classList.toggle("on",k===a);
            });
          });
        })();
    """.trimIndent()
}
