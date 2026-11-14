package nnomm.ui.panel

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import nnomm.core.model.*
import nnomm.ui.LocalApp
import nnomm.ui.kit.*
import nnomm.ui.theme.LocalNnTheme
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** The scroll area of the panel, so that a GIF moves only while it can be seen. */
internal val LocalReadmeViewport = staticCompositionLocalOf<LayoutBoundsHolder?> { null }

/** The Readme tab of the mod panel: the readme of the mod's GitHub repository. */
@Composable
internal fun ReadmeTab(mod: CatalogMod, load: ReadmeLoad?, retry: () -> Unit) {
    val model = LocalApp.current
    val c = LocalNnTheme.current.colors
    val readme = load?.readme
    when {
        load == null || load.status == LoadStatus.LOADING || load.status == LoadStatus.IDLE ->
            Text("Loading the readme…")
        load.status == LoadStatus.FAILED ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not load the readme.")
                Button("Try again", retry, kind = ButtonKind.Line, size = ControlSize.Small)
            }
        readme == null || readme.blocks.isEmpty() ->
            Text(
                if (mod.githubOwner.isNullOrBlank() || mod.githubRepository.isNullOrBlank())
                    "This mod has no GitHub page, so it has no readme."
                else "This mod has no readme on GitHub."
            )
        else -> {
            SelectionContainer { Blocks(readme.blocks, 12.dp) }
            Text("The readme comes from the mod's page on GitHub.", c.text3)
            LinkButton("Open the readme on GitHub", { model.platform.openUrl(readme.pageUrl) })
        }
    }
}

@Composable
private fun Text(text: String, color: Color = LocalNnTheme.current.colors.text2) =
    Label(text, color = color, lineHeight = 19.5.sp)

@Composable
private fun Blocks(blocks: List<ReadmeBlock>, spacing: Dp) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEach { Block(it) }
    }
}

private val LocalListDepth = compositionLocalOf { 0 }

@Composable
private fun Block(block: ReadmeBlock) {
    val t = LocalNnTheme.current
    val c = t.colors
    when (block) {
        is ReadmeBlock.Heading -> {
            val (size, weight) =
                when (block.level) {
                    1 -> 16.5f to 650
                    2 -> 15f to 650
                    3 -> 13.5f to 650
                    else -> 13f to 600
                }
            Rich(
                block.text,
                Modifier.fillMaxWidth()
                    .padding(top = if (block.level <= 2) 6.dp else 2.dp)
                    .then(if (block.level <= 2) Modifier.underline(c.line) else Modifier)
                    .padding(bottom = if (block.level <= 2) 6.dp else 0.dp),
                size = size,
                weight = weight,
                color = c.text,
            )
        }
        is ReadmeBlock.Paragraph -> Rich(block.text, Modifier.fillMaxWidth(), center = block.center)
        is ReadmeBlock.Images ->
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        8.dp,
                        if (block.center) Alignment.CenterHorizontally else Alignment.Start,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.images.forEach { Picture(it) }
            }
        is ReadmeBlock.Code ->
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(t.radius))
                    .background(c.raise)
                    .border(1.dp, c.line, RoundedCornerShape(t.radius))
            ) {
                SideScroll {
                    BasicText(
                        block.text,
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style =
                            t.text(12f)
                                .copy(
                                    color = c.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = null,
                                    lineHeight = (12f * 1.5f * t.textPercent / 100f).sp,
                                ),
                        softWrap = false,
                    )
                }
            }
        is ReadmeBlock.Quote -> {
            val ink =
                when (block.alert) {
                    null -> c.line2
                    ReadmeAlert.NOTE -> c.text2
                    ReadmeAlert.TIP -> c.on
                    ReadmeAlert.IMPORTANT -> c.accentText
                    ReadmeAlert.WARNING -> c.warn
                    ReadmeAlert.CAUTION -> c.danger
                }
            Column(
                Modifier.fillMaxWidth()
                    .drawBehind {
                        drawRect(ink, size = Size(3.dp.toPx(), size.height))
                    }
                    .padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.alert?.let { alert ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (alert == ReadmeAlert.NOTE || alert == ReadmeAlert.TIP) NnIcons.info
                            else NnIcons.alert,
                            color = ink,
                        )
                        Label(alert.title, color = ink, weight = 600)
                    }
                }
                Blocks(block.blocks, 8.dp)
            }
        }
        is ReadmeBlock.Items -> {
            val depth = LocalListDepth.current
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth()) {
                        Label(
                            if (block.ordered) "${block.start + index}."
                            else listOf("•", "◦", "▪")[depth % 3],
                            Modifier.width(if (block.ordered) 22.dp else 16.dp)
                                .alignBy(FirstBaseline),
                            color = c.text3,
                            lineHeight = (13f * 1.5f * t.textPercent / 100f).sp,
                        )
                        Box(Modifier.weight(1f).alignBy(FirstBaseline)) {
                            CompositionLocalProvider(LocalListDepth provides depth + 1) {
                                Blocks(item, 4.dp)
                            }
                        }
                    }
                }
            }
        }
        is ReadmeBlock.Table -> Table(block)
        is ReadmeBlock.Details -> Details(block)
        ReadmeBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
private fun Table(block: ReadmeBlock.Table) {
    val t = LocalNnTheme.current
    val c = t.colors
    val columns = block.rows.maxOf { it.size }
    // Up to three columns share the width; more columns keep a fixed width and scroll sideways.
    val wide = columns > 3
    val grid: @Composable () -> Unit = {
        Column(
            Modifier.then(if (wide) Modifier else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(t.radius))
                .border(1.dp, c.line, RoundedCornerShape(t.radius))
        ) {
            block.rows.forEachIndexed { r, row ->
                val head = block.header && r == 0
                Row(
                    Modifier.then(if (wide) Modifier else Modifier.fillMaxWidth())
                        .background(if (head) c.raise else Color.Transparent)
                        .then(if (r < block.rows.lastIndex) Modifier.underline(c.line) else Modifier)
                ) {
                    for (column in 0 until columns) {
                        val cell = row.getOrNull(column).orEmpty()
                        Box(
                            Modifier.then(
                                    if (wide) Modifier.width(150.dp) else Modifier.weight(1f)
                                )
                                .alignBy(FirstBaseline)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Blocks(if (head) cell.map(::bold) else cell, 4.dp)
                        }
                    }
                }
            }
        }
    }
    if (wide) SideScroll { grid() } else grid()
}

/** Content wider than the panel scrolls sideways; a fade and a scrollbar show that more is there. */
@Composable
private fun SideScroll(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (scroll.canScrollForward)
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Black, Color.Transparent),
                                startX = (size.width - 40.dp.toPx()).coerceAtLeast(0f),
                                endX = size.width,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                }
                .horizontalScroll(scroll)
        ) {
            content()
        }
        if (scroll.maxValue > 0)
            HorizontalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.fillMaxWidth().padding(top = 2.dp),
            )
    }
}

private fun bold(block: ReadmeBlock): ReadmeBlock =
    if (block is ReadmeBlock.Paragraph) block.copy(text = block.text.map { it.copy(bold = true) })
    else block

@Composable
private fun Details(block: ReadmeBlock.Details) {
    val c = LocalNnTheme.current.colors
    var open by remember(block) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        control(Modifier, onClick = { open = !open }) { modifier, hovered, _ ->
            Row(
                modifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (open) NnIcons.chevd else NnIcons.chevr, color = c.text2)
                Rich(block.summary, color = if (hovered) c.text else c.text2, weight = 500)
            }
        }
        if (open) Box(Modifier.padding(start = 20.dp, top = 8.dp)) { Blocks(block.blocks, 10.dp) }
    }
}

@Composable
private fun Rich(
    spans: List<ReadmeSpan>,
    modifier: Modifier = Modifier,
    size: Float = 13f,
    weight: Int = 400,
    color: Color = LocalNnTheme.current.colors.text2,
    center: Boolean = false,
) {
    val model = LocalApp.current
    val t = LocalNnTheme.current
    val c = t.colors
    val scale = t.textPercent / 100f
    val text =
        remember(spans, c, size, weight, scale) {
            buildAnnotatedString {
                for (span in spans) {
                    val style =
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight(maxOf(weight, 650)) else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                            fontFamily = if (span.code) FontFamily.Monospace else null,
                            fontSize =
                                when {
                                    span.small -> (size * .85f * scale).sp
                                    span.code -> (size * .92f * scale).sp
                                    else -> androidx.compose.ui.unit.TextUnit.Unspecified
                                },
                            background = if (span.code) c.raise2 else Color.Unspecified,
                            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                        )
                    val link = span.link
                    if (link == null) withStyle(style) { append(span.text) }
                    else
                        withLink(
                            LinkAnnotation.Url(
                                link,
                                TextLinkStyles(
                                    SpanStyle(color = c.text, textDecoration = TextDecoration.Underline),
                                    hoveredStyle = SpanStyle(color = c.accentText),
                                ),
                            ) {
                                model.platform.openUrl(link)
                            }
                        ) {
                            withStyle(style) { append(span.text) }
                        }
                }
            }
        }
    BasicText(
        text,
        modifier,
        style =
            t.text(size, weight)
                .copy(
                    color = color,
                    lineHeight = (size * 1.5f * scale).sp,
                    textAlign = if (center) TextAlign.Center else TextAlign.Start,
                ),
    )
}

private fun Modifier.underline(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height))
}

/** A decoded picture of a readme. A GIF keeps its file, to play its frames. */
private sealed interface Decoded {
    val width: Int
    val height: Int
    val cost: Long

    class Still(val bitmap: ImageBitmap, override val width: Int, override val height: Int) :
        Decoded {
        override val cost = width.toLong() * height * 4
    }

    class Moving(
        val bytes: ByteArray,
        val first: ImageBitmap,
        override val width: Int,
        override val height: Int,
    ) : Decoded {
        override val cost = bytes.size + width.toLong() * height * 4
    }
}

/** Decoded pictures of this run, the least recently used dropped first. */
private object Pictures {
    private const val BUDGET = 160L * 1024 * 1024
    private val entries = LinkedHashMap<String, Decoded>(64, .75f, true)
    private var used = 0L

    @Synchronized fun get(url: String): Decoded? = entries[url]

    @Synchronized
    fun put(url: String, picture: Decoded) {
        entries.put(url, picture)?.let { used -= it.cost }
        used += picture.cost
        val iterator = entries.entries.iterator()
        while (used > BUDGET && entries.size > 1 && iterator.hasNext()) {
            val oldest = iterator.next()
            if (oldest.key == url) continue
            used -= oldest.value.cost
            iterator.remove()
        }
    }
}

/** Big pictures are made smaller once, because the panel never shows them larger. */
private const val MAX_WIDTH = 1600
private const val MAX_PIXELS = 1600 * 1600

/** Larger pictures are not drawn: their canvas alone would take hundreds of MB. */
private const val MAX_STILL_PIXELS = 25_000_000L
private const val MAX_MOVING_PIXELS = 4_000_000L

private fun decode(bytes: ByteArray): Decoded {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    try {
        // A small file can declare a huge canvas, so check the size before allocating anything.
        val pixels = codec.width.toLong() * codec.height
        require(pixels in 1..MAX_STILL_PIXELS) { "Picture too large" }
        if (codec.frameCount > 1) {
            require(pixels <= MAX_MOVING_PIXELS) { "Moving picture too large" }
            Bitmap().use { bitmap ->
                bitmap.allocPixels(codec.imageInfo)
                codec.readPixels(bitmap, 0)
                return Decoded.Moving(
                    bytes,
                    org.jetbrains.skia.Image.makeFromBitmap(bitmap).toComposeImageBitmap(),
                    codec.width,
                    codec.height,
                )
            }
        }
    } finally {
        codec.close()
    }
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    val scale =
        minOf(
            1f,
            MAX_WIDTH.toFloat() / image.width,
            sqrt(MAX_PIXELS.toFloat() / (image.width.toFloat() * image.height)),
        )
    if (scale >= 1f) return Decoded.Still(image.toComposeImageBitmap(), image.width, image.height)
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    Surface.makeRasterN32Premul(w, h).use { surface ->
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            SamplingMode.MITCHELL,
            null,
            true,
        )
        image.close()
        // The picture keeps its size as the author made it; only its pixels are fewer.
        return Decoded.Still(
            surface.makeImageSnapshot().toComposeImageBitmap(),
            (w / scale).toInt(),
            (h / scale).toInt(),
        )
    }
}

@Composable
private fun Picture(image: ReadmeImage) {
    val model = LocalApp.current
    val t = LocalNnTheme.current
    val c = t.colors
    // A picture loads when it comes into view and lets go of its pixels when it leaves; the
    // cache of decoded pictures keeps recent ones for a quick return.
    var visible by remember(image.url) { mutableStateOf(false) }
    var decoded by remember(image.url) { mutableStateOf<Decoded?>(null) }
    var natural by remember(image.url) { mutableStateOf<Pair<Int, Int>?>(null) }
    var failed by remember(image.url) { mutableStateOf(false) }
    LaunchedEffect(image.url, visible) {
        if (!visible) {
            decoded = null
            return@LaunchedEffect
        }
        try {
            val result =
                Pictures.get(image.url)
                    ?: withContext(Dispatchers.Default) {
                            decode(model.store.readmeImage(image.url))
                        }
                        .also { Pictures.put(image.url, it) }
            natural = result.width to result.height
            decoded = result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) {
        // GitHub writes "image" as the alt text of pasted pictures; it says nothing.
        if (image.alt.isNotBlank() && !image.alt.equals("image", true))
            Label(image.alt, color = c.text3, size = 12f)
        return
    }
    // Pixels count as dp, the way a browser shows a picture at 100 %.
    val size =
        natural?.let { (w, h) ->
            val width = (image.width ?: w).coerceAtLeast(1)
            width to (image.height ?: (h.toLong() * width / w.coerceAtLeast(1)).toInt())
        } ?: image.width?.let { w -> image.height?.let { h -> w to h } }
    val frame =
        Modifier.widthIn(max = (size?.first ?: image.width ?: 480).dp)
            .then(
                if (size != null)
                    Modifier.aspectRatio(size.first.toFloat() / size.second.coerceAtLeast(1))
                else Modifier.fillMaxWidth().height(140.dp)
            )
            .onVisibilityChanged(
                minFractionVisible = 0.01f,
                viewportBounds = LocalReadmeViewport.current,
            ) {
                visible = it
            }
    val picture = decoded
    if (picture == null) {
        Box(
            frame.then(if (size != null) Modifier.fillMaxWidth() else Modifier)
                .background(c.raise, RoundedCornerShape(t.radius))
        )
        return
    }
    // With Reduce motion, a GIF stands still until the player starts it with a click.
    var started by remember(image.url) { mutableStateOf(false) }
    val modifier =
        frame
            .clip(RoundedCornerShape(4.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                if (picture is Decoded.Moving && t.reduceMotion) started = !started
                else model.platform.openUrl(image.link ?: image.url)
            }
    when (picture) {
        is Decoded.Still ->
            Image(picture.bitmap, image.alt.ifBlank { null }, modifier, contentScale = ContentScale.Fit)
        is Decoded.Moving -> Moving(picture, image.alt, modifier, !t.reduceMotion || started)
    }
}

/**
 * Plays a GIF (it exists only while on screen) while the window is not minimised and the game
 * does not run. Frames are decoded one at a time into one bitmap.
 */
@Composable
private fun Moving(picture: Decoded.Moving, alt: String, modifier: Modifier, playing: Boolean) {
    val model = LocalApp.current
    // The current frame, drawn straight onto the canvas. A Compose ImageBitmap per frame would
    // copy each frame into memory that only the garbage collector frees.
    var frame by remember(picture) { mutableStateOf<org.jetbrains.skia.Image?>(null) }
    val gameRunning by
        remember(model) { model.store.state.map { it.gameRunning }.distinctUntilChanged() }
            .collectAsState(false)
    val run = playing && !gameRunning && !nnomm.ui.LocalWindowMinimized.current
    LaunchedEffect(picture, run) {
        if (!run) return@LaunchedEffect
        val codec = withContext(Dispatchers.Default) { Codec.makeFromData(Data.makeFromBytes(picture.bytes)) }
        val bitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
        // Each frame is a copy of the bitmap. A frame is freed four frames later, so that a slow
        // redraw never draws a freed frame.
        val shown = ArrayDeque<org.jetbrains.skia.Image>()
        try {
            val info = codec.framesInfo
            // -1 means for ever; otherwise the number of repeats after the first play.
            val repeats = codec.repetitionCount
            var played = 0
            var index = 0
            var prior = -1
            while (true) {
                val image =
                    withContext(Dispatchers.Default) {
                        try {
                            if (
                                prior >= 0 &&
                                    info[prior].disposalMethod !=
                                        AnimationDisposalMode.RESTORE_PREVIOUS
                            )
                                codec.readPixels(bitmap, index, prior)
                            else codec.readPixels(bitmap, index)
                        } catch (_: Exception) {
                            codec.readPixels(bitmap, index)
                        }
                        org.jetbrains.skia.Image.makeFromBitmap(bitmap)
                    }
                frame = image
                shown.addLast(image)
                if (shown.size > 4) shown.removeFirst().close()
                // Browsers show frames of 10 ms or less for 100 ms.
                delay(info[index].duration.let { if (it <= 10) 100L else it.toLong() })
                if (index == info.size - 1 && repeats >= 0 && ++played > repeats) break
                prior = index
                index = (index + 1) % info.size
                if (index == 0) prior = -1
            }
        } finally {
            // The last frame stays on screen; it is freed with its picture.
            while (shown.size > 1) shown.removeFirst().close()
            bitmap.close()
            codec.close()
        }
    }
    Box(modifier) {
        val current = frame
        if (current == null)
            Image(picture.first, alt.ifBlank { null }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val scale = minOf(size.width / current.width, size.height / current.height)
                    val w = current.width * scale
                    val h = current.height * scale
                    val left = (size.width - w) / 2
                    val top = (size.height - h) / 2
                    drawIntoCanvas {
                        it.nativeCanvas.drawImageRect(
                            current,
                            Rect.makeWH(current.width.toFloat(), current.height.toFloat()),
                            Rect.makeXYWH(left, top, w, h),
                            SamplingMode.LINEAR,
                            null,
                            true,
                        )
                    }
                }
            )
        if (!playing)
            Box(
                Modifier.align(Alignment.Center)
                    .size(36.dp)
                    .background(LocalNnTheme.current.colors.scrim, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NnIcons.play, color = Color.White)
            }
    }
}
