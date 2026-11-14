package nnomm.core.rules

import java.net.URI
import nnomm.core.model.*
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val extensions =
    listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create())
private val markdownParser = Parser.builder().extensions(extensions).build()
private val htmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

/**
 * Parses a GitHub readme. Markdown becomes HTML first (raw HTML in the readme stays), so that
 * Markdown and the HTML that authors mix into it (`<img>`, `<br>`, `<details>`, `<p
 * align="center">`) go through one path. Layout HTML is reduced to its content; badges are left
 * out. Relative addresses resolve against the repository's default branch. A first heading that
 * only repeats [title] is left out, because the panel shows the name above the tabs.
 */
fun parseReadme(text: String, owner: String, repo: String, title: String? = null): Readme {
    val urls = ReadmeUrls(owner, repo)
    val body = Jsoup.parseBodyFragment(htmlRenderer.render(markdownParser.parse(text))).body()
    var blocks = BlockBuilder(urls).apply { children(body, PLAIN) }.finish()
    val first = blocks.firstOrNull()
    if (
        title != null &&
            first is ReadmeBlock.Heading &&
            sameName(first.text.joinToString("") { it.text }, title)
    )
        blocks = blocks.drop(1)
    return Readme(blocks, "https://github.com/$owner/$repo#readme")
}

private val PLAIN = ReadmeSpan("")

private fun sameName(a: String, b: String): Boolean {
    fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    return key(a).isNotEmpty() && key(a) == key(b)
}

private val containers =
    setOf(
        "p",
        "div",
        "center",
        "section",
        "article",
        "header",
        "footer",
        "main",
        "aside",
        "nav",
        "figure",
        "figcaption",
        "address",
        "dl",
        "dt",
        "dd",
        "summary",
        "li",
        "form",
        "fieldset",
    )

private val skipped =
    setOf(
        "script",
        "style",
        "template",
        "iframe",
        "object",
        "embed",
        "svg",
        "math",
        "input",
        "button",
        "select",
        "textarea",
        "head",
        "title",
        "meta",
        "link",
        "source",
        "track",
        "noscript",
    )

private class BlockBuilder(private val urls: ReadmeUrls, private val center: Boolean = false) {
    private val out = mutableListOf<ReadmeBlock>()
    private val spans = mutableListOf<ReadmeSpan>()
    private val images = mutableListOf<ReadmeImage>()

    fun finish(): List<ReadmeBlock> {
        flush()
        return out.toList()
    }

    fun children(parent: Element, style: ReadmeSpan) {
        for (node in parent.childNodes().toList()) node(node, style)
    }

    private fun node(node: Node, style: ReadmeSpan) {
        when (node) {
            is TextNode -> text(node.wholeText, style)
            is Element -> element(node, style)
        }
    }

    private fun text(raw: String, style: ReadmeSpan) {
        val t = raw.replace(Regex("\\s+"), " ")
        if (t.isBlank() && (spans.isEmpty() || images.isNotEmpty())) return
        flushImages()
        spans += style.copy(text = t)
    }

    private fun sub(center: Boolean = false) = BlockBuilder(urls, center)

    private fun element(e: Element, style: ReadmeSpan) {
        when (val tag = e.normalName()) {
            in skipped -> {}
            "br" ->
                if (images.isEmpty() && spans.isNotEmpty())
                    spans += style.copy(text = "\n", link = null)
            "b",
            "strong" -> children(e, style.copy(bold = true))
            "i",
            "em",
            "cite",
            "var",
            "dfn" -> children(e, style.copy(italic = true))
            "code",
            "kbd",
            "samp",
            "tt" -> children(e, style.copy(code = true))
            "del",
            "s",
            "strike" -> children(e, style.copy(strike = true))
            "sub",
            "sup",
            "small" -> children(e, style.copy(small = true))
            "a" -> children(e, style.copy(link = urls.link(e.attr("href")) ?: style.link))
            "img" -> image(e, style)
            "video" -> video(e)
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6" -> heading(e, tag[1].digitToInt(), style)
            "ul",
            "ol" -> list(e, tag == "ol", style)
            "pre" -> {
                flush()
                val code = e.wholeText().trim('\n').trimEnd()
                if (code.isNotBlank()) out += ReadmeBlock.Code(code)
            }
            "blockquote" -> quote(e, style)
            "table" -> table(e, style)
            "hr" -> {
                flush()
                out += ReadmeBlock.Rule
            }
            "details" -> details(e, style)
            in containers -> {
                flush()
                out += sub(centered(e) ?: center).apply { children(e, style) }.finish()
            }
            else -> children(e, style)
        }
    }

    private fun centered(e: Element): Boolean? {
        if (e.normalName() == "center") return true
        val align =
            e.attr("align").ifBlank {
                Regex("text-align\\s*:\\s*(\\w+)").find(e.attr("style"))?.groupValues?.get(1) ?: ""
            }
        return when (align.lowercase()) {
            "center" -> true
            "left",
            "right",
            "justify" -> false
            else -> null
        }
    }

    private fun heading(e: Element, level: Int, style: ReadmeSpan) {
        flush()
        for (block in sub().apply { children(e, style) }.finish()) out +=
            if (block is ReadmeBlock.Paragraph) ReadmeBlock.Heading(level, block.text) else block
    }

    private fun list(e: Element, ordered: Boolean, style: ReadmeSpan) {
        flush()
        val items =
            e.children()
                .filter { it.normalName() == "li" }
                .map { sub().apply { children(it, style) }.finish() }
        if (items.isNotEmpty())
            out += ReadmeBlock.Items(ordered, e.attr("start").trim().toIntOrNull() ?: 1, items)
    }

    private fun quote(e: Element, style: ReadmeSpan) {
        flush()
        val alert = takeAlert(e)
        val inner = sub().apply { children(e, style) }.finish()
        if (inner.isNotEmpty() || alert != null) out += ReadmeBlock.Quote(inner, alert)
    }

    /** GitHub alerts are quotes whose first line is `[!NOTE]` and the like. */
    private fun takeAlert(quote: Element): ReadmeAlert? {
        val p = quote.children().firstOrNull()?.takeIf { it.normalName() == "p" } ?: return null
        val first = p.childNodes().firstOrNull() as? TextNode ?: return null
        val match = Regex("^\\s*\\[!(\\w+)]\\s*").find(first.wholeText) ?: return null
        val alert =
            ReadmeAlert.entries.firstOrNull { it.name.equals(match.groupValues[1], true) }
                ?: return null
        first.text(first.wholeText.substring(match.range.last + 1))
        if (p.text().isBlank() && p.select("img").isEmpty()) p.remove()
        return alert
    }

    private fun table(e: Element, style: ReadmeSpan) {
        flush()
        val rows = e.select("tr").filter { it.closest("table") == e }
        val cells =
            rows
                .map { row ->
                    row.children()
                        .filter { it.normalName() == "td" || it.normalName() == "th" }
                        .map { sub().apply { children(it, style) }.finish() }
                }
                .filter { it.isNotEmpty() }
        if (cells.isEmpty()) return
        val first = rows.first()
        val header =
            first.parent()?.normalName() == "thead" ||
                first.children().all { it.normalName() == "th" }
        out += ReadmeBlock.Table(cells, header)
    }

    private fun details(e: Element, style: ReadmeSpan) {
        flush()
        val summary = e.children().firstOrNull { it.normalName() == "summary" }
        summary?.remove()
        val title =
            summary
                ?.let { sub().apply { children(it, style) }.finish() }
                .orEmpty()
                .flatMap {
                    when (it) {
                        is ReadmeBlock.Paragraph -> it.text
                        is ReadmeBlock.Heading -> it.text
                        else -> emptyList()
                    }
                }
        out +=
            ReadmeBlock.Details(
                title.ifEmpty { listOf(ReadmeSpan("Details")) },
                sub().apply { children(e, style) }.finish(),
            )
    }

    private fun image(e: Element, style: ReadmeSpan) {
        val url = urls.image(e.attr("src")) ?: return
        if (isBadge(url)) return
        flushText()
        images +=
            ReadmeImage(
                url,
                e.attr("alt").trim(),
                style.link,
                pixels(e.attr("width")),
                pixels(e.attr("height")),
            )
    }

    private fun video(e: Element) {
        val src =
            e.attr("src").ifBlank {
                e.children().firstOrNull { it.normalName() == "source" }?.attr("src").orEmpty()
            }
        val url = urls.link(src) ?: return
        flush()
        out += ReadmeBlock.Paragraph(listOf(ReadmeSpan("Watch the video", link = url)), center)
    }

    private fun flush() {
        flushText()
        flushImages()
    }

    private fun flushText() {
        val text = tidy(spans)
        spans.clear()
        if (text.isNotEmpty()) out += ReadmeBlock.Paragraph(text, center)
    }

    private fun flushImages() {
        if (images.isEmpty()) return
        out += ReadmeBlock.Images(images.toList(), center)
        images.clear()
    }
}

/** Joins runs of the same style and removes spaces at the start, at the end and around breaks. */
internal fun tidy(spans: List<ReadmeSpan>): List<ReadmeSpan> {
    val out = ArrayList<ReadmeSpan>()
    for (span in spans) {
        val prev = out.lastOrNull()
        var t = span.text
        if (t == "\n") {
            if (prev == null) continue
            out[out.lastIndex] = prev.copy(text = prev.text.trimEnd(' '))
        } else if (prev == null || prev.text.endsWith(' ') || prev.text.endsWith('\n'))
            t = t.trimStart(' ')
        if (t.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = ""))
            out[out.lastIndex] = last.copy(text = last.text + t)
        else out += span.copy(text = t)
    }
    while (out.isNotEmpty()) {
        val last = out.last()
        val t = last.text.trimEnd(' ', '\n')
        if (t.isEmpty()) out.removeAt(out.lastIndex)
        else {
            out[out.lastIndex] = last.copy(text = t)
            break
        }
    }
    return out.filter { it.text.isNotEmpty() }
}

private fun pixels(value: String): Int? =
    Regex("^\\s*(\\d+)\\s*(px)?\\s*$").find(value)?.groupValues?.get(1)?.toIntOrNull()?.takeIf {
        it in 1..4000
    }

private val badgeHosts =
    setOf(
        "img.shields.io",
        "shields.io",
        "badgen.net",
        "badge.fury.io",
        "forthebadge.com",
        "badges.gitter.im",
        "api.netlify.com",
        "codecov.io",
        "app.codacy.com",
        "ci.appveyor.com",
        "travis-ci.org",
        "travis-ci.com",
    )

/** Status badges say little in the app and are SVG, which the panel does not draw. */
internal fun isBadge(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val path = uri.path.orEmpty()
    return uri.host in badgeHosts || path.endsWith("/badge.svg") || path.contains("/badge/")
}

/** Makes readme addresses absolute, the way GitHub shows the readme on the repository page. */
internal class ReadmeUrls(owner: String, repo: String) {
    private val raw = URI("https://raw.githubusercontent.com/$owner/$repo/HEAD/")
    private val blob = URI("https://github.com/$owner/$repo/blob/HEAD/")
    private val page = "https://github.com/$owner/$repo"

    fun image(src: String): String? {
        val uri = absolute(src, raw) ?: return null
        val secure =
            when (uri.scheme?.lowercase()) {
                "https" -> uri.toString()
                "http" -> "https" + uri.toString().substring(4)
                else -> return null
            }
        // A picture given as its GitHub file page: take the file itself.
        val file =
            Regex("^https://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^?#]+)").find(secure)
                ?: return secure
        val (owner, repo, path) = file.destructured
        return "https://raw.githubusercontent.com/$owner/$repo/$path"
    }

    fun link(href: String): String? {
        val h = href.trim()
        if (h.isEmpty()) return null
        if (h.startsWith("#")) return page + h
        if (h.startsWith("mailto:", true)) return h
        val uri = absolute(h, blob) ?: return null
        return uri.toString().takeIf { uri.scheme?.lowercase() in setOf("https", "http") }
    }

    private fun absolute(src: String, base: URI): URI? =
        try {
            val s = src.trim().replace(" ", "%20")
            when {
                s.isEmpty() || s.startsWith("data:", true) -> null
                s.startsWith("//") -> URI("https:$s")
                s.startsWith("/") -> base.resolve(s.trimStart('/'))
                else -> URI(s).let { if (it.isAbsolute) it else base.resolve(it) }
            }
        } catch (_: Exception) {
            null
        }
}
