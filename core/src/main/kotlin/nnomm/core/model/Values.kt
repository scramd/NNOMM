package nnomm.core.model

import kotlin.math.max

private val versionPart = Regex("^[+-]?\\d+")

internal fun compareVersionValues(a: String, b: String): Int {
    fun parts(v: String) =
        v.substringBefore('-').substringBefore('+').removePrefix("v").split('.').map {
            versionPart.find(it)?.value?.toIntOrNull() ?: 0
        }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until max(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    val aSuffix = a.substringBefore('+').substringAfter('-', "")
    val bSuffix = b.substringBefore('+').substringAfter('-', "")
    return when {
        aSuffix == bSuffix -> 0
        aSuffix.isEmpty() -> 1
        bSuffix.isEmpty() -> -1
        else -> {
            val left = aSuffix.split('.')
            val right = bSuffix.split('.')
            for (i in 0 until minOf(left.size, right.size)) {
                val l = left[i]
                val r = right[i]
                val ln = l.toLongOrNull()
                val rn = r.toLongOrNull()
                val result =
                    when {
                        ln != null && rn != null -> ln.compareTo(rn)
                        ln != null -> -1
                        rn != null -> 1
                        else -> l.compareTo(r)
                    }
                if (result != 0) return result
            }
            left.size.compareTo(right.size)
        }
    }
}

internal fun cleanTagValues(tags: List<String>, authors: List<String>): List<String> {
    val hidden = (authors + "mod").map { it.trim().lowercase() }.toSet()
    return tags
        .map(String::trim)
        .filter { it.isNotEmpty() && it.lowercase() !in hidden }
        .distinctBy { it.lowercase() }
}
