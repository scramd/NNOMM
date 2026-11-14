package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.test.runCurrent
import nnomm.core.model.*
import nnomm.core.model.ReadmeBlock.*
import nnomm.core.rules.parseReadme

class ReadmeParserTest {
    private fun parse(text: String, title: String? = null) =
        parseReadme(text.trimIndent(), "owner", "repo", title).blocks

    private fun plain(spans: List<ReadmeSpan>) = spans.joinToString("") { it.text }

    @Test
    fun markdownBlocks() {
        val blocks =
            parse(
                """
                # Big Mod

                Some **bold** and `code` with a [link](https://example.com/a).

                - one
                - two
                  1. nested

                ```
                line 1
                line 2
                ```

                ---

                | Key | Action |
                | --- | --- |
                | F1 | Open |
                """
            )
        assertEquals(Heading(1, listOf(ReadmeSpan("Big Mod"))), blocks[0])
        val paragraph = blocks[1] as Paragraph
        assertEquals("Some bold and code with a link.", plain(paragraph.text))
        assertTrue(paragraph.text.single { it.text == "bold" }.bold)
        assertTrue(paragraph.text.single { it.text == "code" }.code)
        assertEquals("https://example.com/a", paragraph.text.single { it.text == "link" }.link)
        val list = blocks[2] as Items
        assertFalse(list.ordered)
        assertEquals(2, list.items.size)
        assertTrue(list.items[1][1] is Items)
        assertEquals(Code("line 1\nline 2"), blocks[3])
        assertEquals(Rule, blocks[4])
        val table = blocks[5] as Table
        assertTrue(table.header)
        assertEquals(2, table.rows.size)
        assertEquals("Open", plain((table.rows[1][1].single() as Paragraph).text))
    }

    @Test
    fun firstHeadingThatRepeatsTheNameIsLeftOut() {
        assertTrue(parse("# Big-Mod\n\nText", "Big Mod").first() is Paragraph)
        assertTrue(parse("# Something else\n\nText", "Big Mod").first() is Heading)
    }

    @Test
    fun picturesResolveAgainstTheRepository() {
        val blocks =
            parse(
                """
                ![shot](docs/shot.png) ![anim](/img/fly.gif)

                ![page](https://github.com/a/b/blob/main/x.png?raw=true)

                ![badge](https://img.shields.io/badge/game-0.34-green) Text after a badge
                """
            )
        val row = blocks[0] as Images
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/owner/repo/HEAD/docs/shot.png",
                "https://raw.githubusercontent.com/owner/repo/HEAD/img/fly.gif",
            ),
            row.images.map { it.url },
        )
        assertEquals(
            "https://raw.githubusercontent.com/a/b/main/x.png",
            (blocks[1] as Images).images.single().url,
        )
        assertEquals("Text after a badge", plain((blocks[2] as Paragraph).text))
        assertEquals(3, blocks.size)
    }

    @Test
    fun htmlInTheReadme() {
        val blocks =
            parse(
                """
                <p align="center">
                  <a href="https://example.com/video"><img src="thumb.jpg" width="400" alt="Video"></a>
                </p>

                Line one<br>line two

                <details>
                <summary>Screenshot :</summary>
                <IMG src="readme_content/intercept.png" alt="1.png"/>
                </details>
                """
            )
        val images = blocks[0] as Images
        assertTrue(images.center)
        assertEquals("https://example.com/video", images.images.single().link)
        assertEquals(400, images.images.single().width)
        assertEquals("Line one\nline two", plain((blocks[1] as Paragraph).text))
        val details = blocks[2] as Details
        assertEquals("Screenshot :", plain(details.summary))
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/HEAD/readme_content/intercept.png",
            (details.blocks.single() as Images).images.single().url,
        )
    }

    @Test
    fun alertsAndLinks() {
        val blocks =
            parse(
                """
                > [!WARNING]
                > Keep a copy.

                See [setup](#setup), [docs](docs/SETUP.md), [mail](mailto:a@b.c) and [bad](javascript:alert(1)).
                """
            )
        val quote = blocks[0] as Quote
        assertEquals(ReadmeAlert.WARNING, quote.alert)
        assertEquals("Keep a copy.", plain((quote.blocks.single() as Paragraph).text))
        val links = (blocks[1] as Paragraph).text.associate { it.text to it.link }
        assertEquals("https://github.com/owner/repo#setup", links["setup"])
        assertEquals("https://github.com/owner/repo/blob/HEAD/docs/SETUP.md", links["docs"])
        assertEquals("mailto:a@b.c", links["mail"])
        assertNull(links["bad"])
    }

    @Test
    fun emptyAndPlainText() {
        assertEquals(emptyList(), parse(""))
        assertEquals(
            listOf(Paragraph(listOf(ReadmeSpan("Just text on two lines.")))),
            parse("Just text\non two lines."),
        )
    }
}

class ReadmeParserVideoAndBadgeTest {
    private fun parse(text: String) = parseReadme(text.trimIndent(), "owner", "repo").blocks

    @Test
    fun inlineCodeKeepsItsSpaces() {
        val spans = (parse("Set `a  =  b` here").single() as Paragraph).text
        assertEquals("a  =  b", spans.single { it.code }.text)
    }

    @Test
    fun bareVideoLinkBecomesAShortLink() {
        val url = "https://github.com/user-attachments/assets/10162380-c014-4509-9179-6aee1cb5a91d"
        val span = (parse(url).single() as Paragraph).text.single()
        assertEquals("Watch the video", span.text)
        assertEquals(url, span.link)
        val named = (parse("[demo]($url)").single() as Paragraph).text.single()
        assertEquals("demo", named.text)
    }

    @Test
    fun linkedBadgeKeepsItsLinkAsText() {
        val blocks =
            parse(
                "[![Steam](https://img.shields.io/badge/Steam-Workshop-blue)](https://store.steampowered.com/app/2168680) " +
                    "![build](https://github.com/a/b/actions/workflows/ci.yml/badge.svg) " +
                    "![shot](docs/badge/shot.png)"
            )
        val text = blocks[0] as Paragraph
        assertEquals("Steam", text.text.single().text)
        assertEquals("https://store.steampowered.com/app/2168680", text.text.single().link)
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/HEAD/docs/badge/shot.png",
            (blocks[1] as Images).images.single().url,
        )
        assertEquals(2, blocks.size)
    }
}

class ReadmeLoadTest {
    @Test
    fun loadsOnceAndTriesAgainAfterAFailure() =
        kotlinx.coroutines.test.runTest {
            var calls = 0
            var fail = true
            val source =
                object : nnomm.core.io.ReadmeSource {
                    override suspend fun readme(mod: CatalogMod): Readme? {
                        calls++
                        if (fail) throw java.io.IOException("offline")
                        return parseReadme("Hello", "o", "r")
                    }

                    override suspend fun image(url: String) = ByteArray(0)
                }
            val store =
                nnomm.core.store.AppStore(
                    nnomm.core.demo.Demo.state(),
                    nnomm.core.demo.Demo.io().copy(readme = source),
                    backgroundScope,
                )
            store.loadReadme("minimap")
            assertEquals(LoadStatus.LOADING, store.readmes.value["minimap"]?.status)
            runCurrent()
            assertEquals(LoadStatus.FAILED, store.readmes.value["minimap"]?.status)
            fail = false
            store.loadReadme("minimap")
            runCurrent()
            assertEquals(LoadStatus.READY, store.readmes.value["minimap"]?.status)
            assertNotNull(store.readmes.value["minimap"]?.readme)
            store.loadReadme("minimap")
            runCurrent()
            assertEquals(2, calls)
            store.close()
        }
}

class RealReadmesTest {
    @Test
    fun findsTheReadmeKeepsItAndUsesTheSavedCopyOffline() =
        kotlinx.coroutines.runBlocking {
            val home = kotlin.io.path.createTempDirectory("readmes")
            val requests = mutableListOf<String>()
            var down = false
            val server =
                com.sun.net.httpserver.HttpServer.create(
                    java.net.InetSocketAddress("127.0.0.1", 0),
                    0,
                )
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                synchronized(requests) { requests += path }
                val body =
                    when {
                        down -> null
                        path == "/o/r/HEAD/readme.md" -> "# R\n\nText".toByteArray()
                        else -> null
                    }
                exchange.sendResponseHeaders(
                    if (down) 500 else if (body == null) 404 else 200,
                    body?.size?.toLong() ?: -1,
                )
                body?.let { exchange.responseBody.write(it) }
                exchange.close()
            }
            server.start()
            val client = nnomm.core.io.real.networkClient("test")
            try {
                val host = "http://127.0.0.1:${server.address.port}"
                val mod =
                    CatalogMod("m", "M", listOf("a"), "", githubOwner = "o", githubRepository = "r")
                val first = nnomm.core.io.real.RealReadmes(client, home, host).readme(mod)
                assertEquals("Text", (first!!.blocks.last() as Paragraph).text.single().text)
                assertEquals(listOf("/o/r/HEAD/README.md", "/o/r/HEAD/readme.md"), requests)
                down = true
                // A new run while GitHub is down: the saved copy is used.
                val offline = nnomm.core.io.real.RealReadmes(client, home, host).readme(mod)
                assertEquals(first, offline)
                // A repository without a readme is null, not an error.
                down = false
                assertNull(
                    nnomm.core.io.real
                        .RealReadmes(client, home, host)
                        .readme(mod.copy(githubRepository = "none"))
                )
            } finally {
                client.close()
                server.stop(0)
                home.toFile().deleteRecursively()
            }
        }
}
