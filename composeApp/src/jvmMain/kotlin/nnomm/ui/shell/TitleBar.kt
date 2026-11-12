package nnomm.ui.shell

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nnomm.ui.kit.*
import nnomm.ui.theme.*

/** Drag handling belongs only to blank title-bar regions, never the command or window buttons. */
@Composable
fun TitleBar(
    onCommand: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp).background(c.side)) {
        val windowWidth = maxWidth
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight().then(dragModifier).padding(start = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (windowWidth > 940.dp) Label("NNOMM", weight = 750, width = 122f)
            }
            Spacer(Modifier.weight(1f).fillMaxHeight().then(dragModifier))
            listOf(
                    Triple(NnIcons.minus, "Minimize", onMinimize),
                    Triple(NnIcons.square, "Maximize or restore", onMaximize),
                    Triple(NnIcons.x, "Close", onClose),
                )
                .forEach { (icon, description, action) ->
                    control(Modifier, onClick = action) { m, h, _ ->
                        Box(
                            m.size(44.dp, 38.dp).background(if (h) c.raise2 else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, color = c.text2, description = description)
                        }
                    }
                }
        }
        control(
            Modifier.align(Alignment.Center).widthIn(max = 380.dp).width(windowWidth * .46f),
            onClick = onCommand,
        ) { m, h, _ ->
            Row(
                m.height(26.dp)
                    .background(c.bg, RoundedCornerShape(6.dp))
                    .border(1.dp, if (h) c.line2 else c.line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(NnIcons.search, color = c.text3)
                Label(
                    "Search or run a command",
                    Modifier.weight(1f),
                    color = if (h) c.text2 else c.text3,
                )
                KeyCap("Ctrl K")
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
fun WindowShell(
    onCommand: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onMaximize: () -> Unit = {},
    onClose: () -> Unit = {},
    dragModifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(LocalNnTheme.current.colors.bg)
            .border(1.dp, LocalNnTheme.current.colors.line2, RoundedCornerShape(10.dp))
            .padding(1.dp)
    ) {
        TitleBar(onCommand, onMinimize, onMaximize, onClose, dragModifier)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
