package nnomm.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.*

data class NnColors(
    val olive: Color,
    val cream: Color,
    val orange: Color,
    val darkOlive: Color,
    val bg: Color,
    val side: Color,
    val raise: Color,
    val raise2: Color,
    val line: Color,
    val line2: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val on: Color,
    val onFill: Color,
    val onInk: Color,
    val accent: Color,
    val accentInk: Color,
    val accentText: Color,
    val strong: Color,
    val strongInk: Color,
    val warn: Color,
    val warnBg: Color,
    val danger: Color,
    val dangerBg: Color,
    val mark: Color,
    val shadow: Color,
    val scrim: Color,
)

val DarkColors =
    NnColors(
        olive = Color(0xff4b5320),
        cream = Color(0xffe8e2c8),
        orange = Color(0xffe0552e),
        darkOlive = Color(0xff353b17),
        bg = Color(0xff141513),
        side = Color(0xff191a17),
        raise = Color(0xff1e201c),
        raise2 = Color(0xff282a25),
        line = Color(0xff2a2c27),
        line2 = Color(0xff3a3d35),
        text = Color(0xffe8e2c8),
        text2 = Color(0xffb4af9a),
        text3 = Color(0xff8b8878),
        on = Color(0xffa9b565),
        onFill = Color(0xff4b5320),
        onInk = Color(0xffe8e2c8),
        accent = Color(0xffe0552e),
        accentInk = Color(0xff1c0c05),
        accentText = Color(0xffee7a55),
        strong = Color(0xffe8e2c8),
        strongInk = Color(0xff1b1d12),
        warn = Color(0xffd9ac3d),
        warnBg = Color(0xff3a3116),
        danger = Color(0xfff0506e),
        dangerBg = Color(0xff3d1722),
        mark = Color(0.9098039215686274f, 0.8862745098039215f, 0.7843137254901961f, 0.24f),
        shadow = Color(0.0f, 0.0f, 0.0f, 0.55f),
        scrim = Color(0.03137254901960784f, 0.03529411764705882f, 0.027450980392156862f, 0.6f),
    )
val LightColors =
    NnColors(
        olive = Color(0xff4b5320),
        cream = Color(0xffe8e2c8),
        orange = Color(0xffe0552e),
        darkOlive = Color(0xff353b17),
        bg = Color(0xfff6f5f0),
        side = Color(0xffeeede6),
        raise = Color(0xffffffff),
        raise2 = Color(0xffe7e5db),
        line = Color(0xffdcdace),
        line2 = Color(0xffc4c2b3),
        text = Color(0xff272b12),
        text2 = Color(0xff54573f),
        text3 = Color(0xff777865),
        on = Color(0xff4b5320),
        onFill = Color(0xff4b5320),
        onInk = Color(0xfff3efdc),
        accent = Color(0xffe0552e),
        accentInk = Color(0xff1c0c05),
        accentText = Color(0xffb63c18),
        strong = Color(0xff353b17),
        strongInk = Color(0xfff3efdc),
        warn = Color(0xff8a6410),
        warnBg = Color(0xfff3e6bd),
        danger = Color(0xffb81d3f),
        dangerBg = Color(0xfff7d6dd),
        mark = Color(0.29411764705882354f, 0.3254901960784314f, 0.12549019607843137f, 0.22f),
        shadow = Color(0.1568627450980392f, 0.16470588235294117f, 0.09803921568627451f, 0.25f),
        scrim = Color(0.1568627450980392f, 0.16470588235294117f, 0.09803921568627451f, 0.35f),
    )

data class NnTheme(
    val colors: NnColors,
    val textPercent: Int = 100,
    val comfortable: Boolean = false,
    val reduceMotion: Boolean = false,
) {
    val radius = 6.dp
    val radiusLarge = 9.dp
    val rowHeight
        get() = if (comfortable) 40.dp else 34.dp

    val panelMillis
        get() = if (reduceMotion) 0 else 160

    val controlMillis
        get() = if (reduceMotion) 0 else 120

    val progressMillis
        get() = if (reduceMotion) 0 else 250

    val shadowBlur = 50.dp
    val shadowOffsetY = 18.dp

    fun text(size: Float = 13f, weight: Int = 400, width: Float = 100f) =
        TextStyle(
            color = colors.text,
            fontFamily = archivo(width),
            fontSize = (size * textPercent / 100f).sp,
            fontWeight = FontWeight(weight),
            fontFeatureSettings = "tnum",
            lineHeight = (size * 1.4f * textPercent / 100f).sp,
        )
}

private val fontBytes by lazy {
    checkNotNull(NnTheme::class.java.getResourceAsStream("/fonts/Archivo[wdth,wght].ttf")).use {
        it.readBytes()
    }
}
private val fonts = mutableMapOf<Float, FontFamily>()

fun archivo(width: Float = 100f): FontFamily =
    fonts.getOrPut(width) {
        FontFamily(
            (100..900 step 50).map { weight ->
                Font(
                    "Archivo-$width-$weight",
                    fontBytes,
                    weight = FontWeight(weight),
                    variationSettings =
                        FontVariation.Settings(
                            FontVariation.width(width),
                            FontVariation.weight(weight),
                        ),
                )
            }
        )
    }

val LocalNnTheme = staticCompositionLocalOf { NnTheme(DarkColors) }

@Composable
fun NnommTheme(
    dark: Boolean = true,
    textPercent: Int = 100,
    comfortable: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val theme =
        remember(dark, textPercent, comfortable, reduceMotion) {
            NnTheme(if (dark) DarkColors else LightColors, textPercent, comfortable, reduceMotion)
        }
    CompositionLocalProvider(
        LocalNnTheme provides theme,
        LocalScrollbarStyle provides
            ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 6.dp,
                shape = RoundedCornerShape(5.dp),
                hoverDurationMillis = theme.controlMillis,
                unhoverColor = theme.colors.line2,
                hoverColor = theme.colors.text3,
            ),
        content = content,
    )
}
