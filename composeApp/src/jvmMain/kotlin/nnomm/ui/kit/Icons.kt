package nnomm.ui.kit

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

/** Vector paths transcribed from ICONS and PICS in design/mock/src/core.js. */
object NnIcons {
    val search: ImageVector by lazy {
        ImageVector.Builder("search", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M2.5 7.0a4.5 4.5 0 1 0 9.0 0a4.5 4.5 0 1 0 -9.0 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M10.5 10.5 14 14").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val browse: ImageVector by lazy {
        ImageVector.Builder("browse", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M2.0 8.0a6.0 6.0 0 1 0 12.0 0a6.0 6.0 0 1 0 -12.0 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("m10.6 5.4-1.5 3.7-3.7 1.5 1.5-3.7z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val library: ImageVector by lazy {
        ImageVector.Builder("library", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 2 2 5l6 3 6-3-6-3z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("m2 8 6 3 6-3").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("m2 11 6 3 6-3").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val server: ImageVector by lazy {
        ImageVector.Builder("server", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M3.0 2.5h10.0q1.0 0 1.0 1.0v2.5q0 1.0 -1.0 1.0h-10.0q-1.0 0 -1.0 -1.0v-2.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString(
                            "M3.0 9.0h10.0q1.0 0 1.0 1.0v2.5q0 1.0 -1.0 1.0h-10.0q-1.0 0 -1.0 -1.0v-2.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M4.6 4.75h.01M4.6 11.25h.01").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val gear: ImageVector by lazy {
        ImageVector.Builder("gear", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M5.8 8.0a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString(
                            "M8 1.6v2M8 12.4v2M1.6 8h2M12.4 8h2M3.5 3.5l1.4 1.4M11.1 11.1l1.4 1.4M3.5 12.5l1.4-1.4M11.1 4.9l1.4-1.4"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2.0f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val play: ImageVector by lazy {
        ImageVector.Builder("play", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M5 3.2v9.6l7.5-4.8z").toNodes(),
                    fill = SolidColor(Color.Black),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val download: ImageVector by lazy {
        ImageVector.Builder("download", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M8 2.5v8M4.5 7.5 8 11l3.5-3.5M3 13.5h10")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val check: ImageVector by lazy {
        ImageVector.Builder("check", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("m3.5 8.5 3 3 6-7").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val x: ImageVector by lazy {
        ImageVector.Builder("x", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("m4 4 8 8M12 4l-8 8").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val chevd: ImageVector by lazy {
        ImageVector.Builder("chevd", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("m4 6 4 4 4-4").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val chevu: ImageVector by lazy {
        ImageVector.Builder("chevu", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("m4 10 4-4 4 4").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val chevr: ImageVector by lazy {
        ImageVector.Builder("chevr", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("m6 4 4 4-4 4").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val plus: ImageVector by lazy {
        ImageVector.Builder("plus", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 3v10M3 8h10").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val minus: ImageVector by lazy {
        ImageVector.Builder("minus", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M3 8h10").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val eyeOff: ImageVector by lazy {
        ImageVector.Builder("eyeoff", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M2 8s2.2-4 6-4c1 0 1.9.3 2.6.7M14 8s-2.2 4-6 4c-1 0-1.9-.3-2.6-.7M2.5 2.5l11 11"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val more: ImageVector by lazy {
        ImageVector.Builder("more", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M2.5 8.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0")
                        .toNodes(),
                    fill = SolidColor(Color.Black),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString("M7.0 8.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0")
                        .toNodes(),
                    fill = SolidColor(Color.Black),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString("M11.5 8.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0")
                        .toNodes(),
                    fill = SolidColor(Color.Black),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val star: ImageVector by lazy {
        ImageVector.Builder("star", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "m8 2 1.8 3.8 4.2.5-3.1 2.9.8 4.1L8 11.3l-3.7 2 .8-4.1L2 6.3l4.2-.5z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val lock: ImageVector by lazy {
        ImageVector.Builder("lock", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M4.5 7.0h7.0q1.0 0 1.0 1.0v4.5q0 1.0 -1.0 1.0h-7.0q-1.0 0 -1.0 -1.0v-4.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M5.5 7V5a2.5 2.5 0 0 1 5 0v2").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val clock: ImageVector by lazy {
        ImageVector.Builder("clock", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M2.0 8.0a6.0 6.0 0 1 0 12.0 0a6.0 6.0 0 1 0 -12.0 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M8 4.5V8l2.5 1.5").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val alert: ImageVector by lazy {
        ImageVector.Builder("alert", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 2 1.5 13.5h13z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M8 6.5v3.5M8 12h.01").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val folder: ImageVector by lazy {
        ImageVector.Builder("folder", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M2 4.5h4l1.5 1.5H14v7H2z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val share: ImageVector by lazy {
        ImageVector.Builder("share", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 10V2.5M5 5l3-3 3 3M3 9v4.5h10V9").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val box: ImageVector by lazy {
        ImageVector.Builder("box", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 1.5 2 4.5v7l6 3 6-3v-7z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("m2 4.5 6 3 6-3M8 7.5v7").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val grid: ImageVector by lazy {
        ImageVector.Builder("grid", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M3.5 2.5h2.5q1.0 0 1.0 1.0v2.5q0 1.0 -1.0 1.0h-2.5q-1.0 0 -1.0 -1.0v-2.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString(
                            "M10.0 2.5h2.5q1.0 0 1.0 1.0v2.5q0 1.0 -1.0 1.0h-2.5q-1.0 0 -1.0 -1.0v-2.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString(
                            "M3.5 9.0h2.5q1.0 0 1.0 1.0v2.5q0 1.0 -1.0 1.0h-2.5q-1.0 0 -1.0 -1.0v-2.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString(
                            "M10.0 9.0h2.5q1.0 0 1.0 1.0v2.5q0 1.0 -1.0 1.0h-2.5q-1.0 0 -1.0 -1.0v-2.5q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val list: ImageVector by lazy {
        ImageVector.Builder("list", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M2.5 4h11M2.5 8h11M2.5 12h11").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val filter: ImageVector by lazy {
        ImageVector.Builder("filter", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M2 4h12M4.5 8h7M6.5 12h3").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val undo: ImageVector by lazy {
        ImageVector.Builder("undo", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M3 6.5h7a3.5 3.5 0 0 1 0 7H6M3 6.5l3-3M3 6.5l3 3")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val up: ImageVector by lazy {
        ImageVector.Builder("up", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 13V3M4 7l4-4 4 4").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val ext: ImageVector by lazy {
        ImageVector.Builder("ext", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M6.5 3.5H3V13h9.5V9.5M9 2.5h4.5V7M13.5 2.5l-6 6")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val trash: ImageVector by lazy {
        ImageVector.Builder("trash", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M2.5 4.5h11M6 4.5v-2h4v2M4 4.5l.7 9h6.6l.7-9")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val refresh: ImageVector by lazy {
        ImageVector.Builder("refresh", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M13.5 8a5.5 5.5 0 1 1-1.6-3.9M13.5 2.5v3h-3")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val split: ImageVector by lazy {
        ImageVector.Builder("split", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M8 2v12M2.5 5h3M2.5 8h3M2.5 11h3M10.5 5h3M10.5 8h3M10.5 11h3"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val users: ImageVector by lazy {
        ImageVector.Builder("users", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M3.5 5.5a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser()
                        .parsePathString(
                            "M1.5 13.5c.5-2.5 2.2-4 4.5-4s4 1.5 4.5 4M10.5 3.2a2.5 2.5 0 0 1 0 4.6M12 9.8c1.4.6 2.2 1.9 2.5 3.7"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val file: ImageVector by lazy {
        ImageVector.Builder("file", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser().parsePathString("M4 1.5h5l3 3v10H4z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M9 1.5v3h3").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val info: ImageVector by lazy {
        ImageVector.Builder("info", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M2.0 8.0a6.0 6.0 0 1 0 12.0 0a6.0 6.0 0 1 0 -12.0 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M8 7.5V11M8 5h.01").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val square: ImageVector by lazy {
        ImageVector.Builder("square", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M4.0 4.0h8.0q0.0 0 0.0 0.0v8.0q0 0.0 -0.0 0.0h-8.0q-0.0 0 -0.0 -0.0v-8.0q0 -0.0 0.0 -0.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val none: ImageVector by lazy {
        ImageVector.Builder("none", 16.dp, 16.dp, 16f, 16f).apply {}.build()
    }
    val copy: ImageVector by lazy {
        ImageVector.Builder("copy", 16.dp, 16.dp, 16f, 16f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M6.5 5.5h6.0q1.0 0 1.0 1.0v6.0q0 1.0 -1.0 1.0h-6.0q-1.0 0 -1.0 -1.0v-6.0q0 -1.0 1.0 -1.0z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                addPath(
                    PathParser().parsePathString("M3 10.5h-.5v-8h8V3").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            .build()
    }
    val all by lazy {
        mapOf(
            "search" to search,
            "browse" to browse,
            "library" to library,
            "server" to server,
            "gear" to gear,
            "play" to play,
            "download" to download,
            "check" to check,
            "x" to x,
            "chevd" to chevd,
            "chevu" to chevu,
            "chevr" to chevr,
            "plus" to plus,
            "minus" to minus,
            "more" to more,
            "star" to star,
            "lock" to lock,
            "clock" to clock,
            "alert" to alert,
            "folder" to folder,
            "share" to share,
            "box" to box,
            "grid" to grid,
            "list" to list,
            "filter" to filter,
            "undo" to undo,
            "up" to up,
            "ext" to ext,
            "trash" to trash,
            "refresh" to refresh,
            "split" to split,
            "users" to users,
            "file" to file,
            "info" to info,
            "square" to square,
            "none" to none,
            "copy" to copy,
        )
    }
    val picCog: ImageVector by lazy {
        ImageVector.Builder("picCog", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M27.0 20.0 A7 7 0 0 1 26.281220831255258 23.0897030389675 M24.94818249537211 24.951311946594867 A7 7 0 0 1 22.254649056930525 26.626956890615958 M19.995574287859128 26.999998600933576 A7 7 0 0 1 16.906326310662127 26.279266127812914 M15.045560581386457 24.94505106619621 A7 7 0 0 1 13.371618944219502 22.250458748648864 M13.00000559626513 19.99114857748736 A7 7 0 0 1 13.722691085660971 16.902356896936034 M15.058082339681084 15.042435089822018 A7 7 0 0 1 17.753732459216018 13.37019742863935 M20.013277129346204 13.000012591594444 A7 7 0 0 1 23.101611278559076 13.724650808384036 M24.96068842003882 15.061217721007257 A7 7 0 0 1 26.631221436788184 17.757924564988755"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color(0xffe8e2c8)),
                    strokeLineWidth = 5.0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
                addPath(
                    PathParser()
                        .parsePathString("M17.0 20.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0")
                        .toNodes(),
                    fill = SolidColor(Color(0xffe8e2c8)),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picFrame: ImageVector by lazy {
        ImageVector.Builder("picFrame", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser().parsePathString("M8 28 20 8l12 20z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color(0xffe8e2c8)),
                    strokeLineWidth = 2.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
                addPath(
                    PathParser().parsePathString("M14 28h12").toNodes(),
                    fill = SolidColor(Color.Black),
                    stroke = SolidColor(Color(0xffe0552e)),
                    strokeLineWidth = 3.0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picMap: ImageVector by lazy {
        ImageVector.Builder("picMap", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser().parsePathString("M6 12l9-3 10 3 9-3v19l-9 3-10-3-9 3z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color(0xffe8e2c8)),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
                addPath(
                    PathParser()
                        .parsePathString("M21.0 19.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0")
                        .toNodes(),
                    fill = SolidColor(Color(0xffe0552e)),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picEye: ImageVector by lazy {
        ImageVector.Builder("picEye", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M5 20c4-7 9-10 15-10s11 3 15 10c-4 7-9 10-15 10S9 27 5 20z"
                        )
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color(0xffe8e2c8)),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
                addPath(
                    PathParser()
                        .parsePathString("M15.5 20.0a4.5 4.5 0 1 0 9.0 0a4.5 4.5 0 1 0 -9.0 0")
                        .toNodes(),
                    fill = SolidColor(Color(0xffe8e2c8)),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picGlobe: ImageVector by lazy {
        ImageVector.Builder("picGlobe", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M8.0 20.0a12.0 12.0 0 1 0 24.0 0a12.0 12.0 0 1 0 -24.0 0")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color(0xffe8e2c8)),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
                addPath(
                    PathParser()
                        .parsePathString("M8 20h24M20 8c-6 7-6 17 0 24M20 8c6 7 6 17 0 24")
                        .toNodes(),
                    fill = null,
                    stroke = SolidColor(Color(0xffe8e2c8)),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picJet: ImageVector by lazy {
        ImageVector.Builder("picJet", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString(
                            "M20 5l3 12 11 7v3l-11-3-1 7 4 3v2l-6-2-6 2v-2l4-3-1-7-11 3v-3l11-7z"
                        )
                        .toNodes(),
                    fill = SolidColor(Color(0xffe8e2c8)),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picJet2: ImageVector by lazy {
        ImageVector.Builder("picJet2", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser()
                        .parsePathString("M20 4l4 16 10 10v2l-11-4-3 6-3-6-11 4v-2l10-10z")
                        .toNodes(),
                    fill = SolidColor(Color(0xffe8e2c8)),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val picWing: ImageVector by lazy {
        ImageVector.Builder("picWing", 40.dp, 40.dp, 40f, 40f)
            .apply {
                addPath(
                    PathParser().parsePathString("M6 26l14-12 14 12-14-5z").toNodes(),
                    fill = SolidColor(Color(0xffe8e2c8)),
                    stroke = null,
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
                addPath(
                    PathParser().parsePathString("M20 14v-6").toNodes(),
                    fill = SolidColor(Color.Black),
                    stroke = SolidColor(Color(0xffe0552e)),
                    strokeLineWidth = 3.0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
            .build()
    }
    val pictures by lazy {
        mapOf(
            "cog" to picCog,
            "frame" to picFrame,
            "map" to picMap,
            "eye" to picEye,
            "globe" to picGlobe,
            "jet" to picJet,
            "jet2" to picJet2,
            "wing" to picWing,
        )
    }
}

/** Copy the mock's path geometry with a control-specific stroke. */
internal fun ImageVector.withStroke(width: Float): ImageVector {
    val builder =
        ImageVector.Builder(name, defaultWidth, defaultHeight, viewportWidth, viewportHeight)
    fun copy(group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorPath ->
                    builder.addPath(
                        pathData = node.pathData,
                        pathFillType = node.pathFillType,
                        fill = node.fill,
                        fillAlpha = node.fillAlpha,
                        stroke = node.stroke,
                        strokeAlpha = node.strokeAlpha,
                        strokeLineWidth = width,
                        strokeLineCap = node.strokeLineCap,
                        strokeLineJoin = node.strokeLineJoin,
                        strokeLineMiter = node.strokeLineMiter,
                    )
                is VectorGroup -> {
                    builder.addGroup(
                        name = node.name,
                        rotate = node.rotation,
                        pivotX = node.pivotX,
                        pivotY = node.pivotY,
                        scaleX = node.scaleX,
                        scaleY = node.scaleY,
                        translationX = node.translationX,
                        translationY = node.translationY,
                        clipPathData = node.clipPathData,
                    )
                    copy(node)
                    builder.clearGroup()
                }
            }
        }
    }
    copy(root)
    return builder.build()
}
