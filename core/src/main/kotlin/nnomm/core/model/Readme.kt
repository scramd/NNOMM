package nnomm.core.model

/**
 * A mod's readme from its GitHub repository, parsed into the few kinds of block that the mod panel
 * draws. Addresses in it are absolute and HTTPS (or `mailto:` for links).
 */
data class Readme(val blocks: List<ReadmeBlock>, val pageUrl: String)

/** The readme of one mod while it loads; [readme] is null when the mod has no readme. */
data class ReadmeLoad(val status: LoadStatus, val readme: Readme? = null)

sealed interface ReadmeBlock {
    data class Heading(val level: Int, val text: List<ReadmeSpan>) : ReadmeBlock

    data class Paragraph(val text: List<ReadmeSpan>, val center: Boolean = false) : ReadmeBlock

    /** Pictures that stand together, in one row that wraps. */
    data class Images(val images: List<ReadmeImage>, val center: Boolean = false) : ReadmeBlock

    data class Code(val text: String) : ReadmeBlock

    data class Quote(val blocks: List<ReadmeBlock>, val alert: ReadmeAlert? = null) : ReadmeBlock

    data class Items(val ordered: Boolean, val start: Int, val items: List<List<ReadmeBlock>>) :
        ReadmeBlock

    /** Rows of cells; each cell holds blocks. The first row is a header when [header] is true. */
    data class Table(val rows: List<List<List<ReadmeBlock>>>, val header: Boolean) : ReadmeBlock

    /** A `<details>` section: closed until the player opens it, as on GitHub. */
    data class Details(val summary: List<ReadmeSpan>, val blocks: List<ReadmeBlock>) : ReadmeBlock

    data object Rule : ReadmeBlock
}

/** GitHub's alert kinds (`> [!NOTE]`). */
enum class ReadmeAlert(val title: String) {
    NOTE("Note"),
    TIP("Tip"),
    IMPORTANT("Important"),
    WARNING("Warning"),
    CAUTION("Caution"),
}

/** A run of text with one style. A line break is the text "\n". */
data class ReadmeSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val small: Boolean = false,
    val link: String? = null,
)

/** [width] and [height] are the sizes the author set in the HTML, in pixels, if any. */
data class ReadmeImage(
    val url: String,
    val alt: String,
    val link: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
