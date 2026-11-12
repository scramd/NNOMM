package nnomm.ui.kit

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nnomm.ui.theme.LocalNnTheme

@Composable
fun Label(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = LocalNnTheme.current.colors.text,
    size: Float = 13f,
    weight: Int = 400,
    width: Float = 100f,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign = TextAlign.Start,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val marks =
        text.spanStyles.filter {
            it.item.background != Color.Unspecified && it.item.background.alpha > 0f
        }
    val foreground =
        remember(text) {
            buildAnnotatedString {
                append(text)
                marks.forEach {
                    addStyle(SpanStyle(background = Color.Transparent), it.start, it.end)
                }
            }
        }
    BasicText(
        foreground,
        modifier.drawWithContent {
            layout?.let { result ->
                clipRect {
                    marks.forEach { mark ->
                        for (line in 0 until result.lineCount) {
                            val start = maxOf(mark.start, result.getLineStart(line))
                            val end = minOf(mark.end, result.getLineEnd(line, visibleEnd = true))
                            if (start < end) {
                                val first = result.getBoundingBox(start)
                                val last = result.getBoundingBox(end - 1)
                                val left = minOf(first.left, last.left)
                                val right = maxOf(first.right, last.right)
                                drawRoundRect(
                                    mark.item.background,
                                    topLeft = Offset(left, first.top),
                                    size = Size(right - left, first.height),
                                    cornerRadius = CornerRadius(2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
            drawContent()
        },
        style =
            LocalNnTheme.current
                .text(size, weight, width)
                .copy(color = color, textAlign = textAlign),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it },
    )
}

/** Indices are UTF-16 character positions, as returned by the mock's search functions. */
fun highlightedText(
    text: String,
    indices: Iterable<Int>,
    background: Color,
    ink: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    val positions = indices.filter { it in text.indices }.distinct().sorted()
    var offset = 0
    while (offset < positions.size) {
        val start = positions[offset]
        var end = start + 1
        offset++
        while (offset < positions.size && positions[offset] == end) {
            end++
            offset++
        }
        addStyle(SpanStyle(color = ink, background = background), start, end)
    }
}

@Composable
fun highlightedText(text: String, indices: Iterable<Int>): AnnotatedString {
    val colors = LocalNnTheme.current.colors
    return highlightedText(text, indices, colors.mark, colors.text)
}
