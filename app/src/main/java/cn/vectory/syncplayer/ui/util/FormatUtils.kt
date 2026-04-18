package cn.vectory.syncplayer.ui.util

import java.text.Collator
import java.util.Locale

private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
    strength = Collator.PRIMARY
    decomposition = Collator.CANONICAL_DECOMPOSITION
}

private data class SortToken(val value: String, val isNumeric: Boolean)

private fun tokenizeNaturalSort(s: String): List<SortToken> {
    val tokens = mutableListOf<SortToken>()
    var i = 0
    while (i < s.length) {
        if (s[i].isDigit()) {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            tokens.add(SortToken(s.substring(start, i), true))
        } else {
            val start = i
            while (i < s.length && !s[i].isDigit()) i++
            tokens.add(SortToken(s.substring(start, i), false))
        }
    }
    return tokens
}

internal val fileNameComparator: Comparator<String> = Comparator { a, b ->
    val tokensA = tokenizeNaturalSort(a)
    val tokensB = tokenizeNaturalSort(b)
    val minLen = minOf(tokensA.size, tokensB.size)
    for (i in 0 until minLen) {
        val tA = tokensA[i]
        val tB = tokensB[i]
        val cmp = when {
            tA.isNumeric && tB.isNumeric -> {
                val effectiveA = tA.value.trimStart('0').let { if (it.isEmpty()) "0" else it }
                val effectiveB = tB.value.trimStart('0').let { if (it.isEmpty()) "0" else it }
                when {
                    effectiveA.length != effectiveB.length -> effectiveA.length - effectiveB.length
                    else -> {
                        val numCmp = effectiveA.compareTo(effectiveB)
                        if (numCmp != 0) numCmp else tA.value.length - tB.value.length
                    }
                }
            }
            tA.isNumeric && !tB.isNumeric -> -1
            !tA.isNumeric && tB.isNumeric -> 1
            else -> collator.compare(tA.value, tB.value)
        }
        if (cmp != 0) return@Comparator cmp
    }
    tokensA.size - tokensB.size
}

internal fun formatDuration(ms: Long?): String {
    if (ms == null) return ""
    if (ms <= 0) return "0:00"

    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun buildMetadataLine(
    duration: Long?,
    width: Int?,
    height: Int?,
    bitrate: Long?,
    extension: String,
    hasGainAdjusted: Boolean = false
): String {
    val parts = mutableListOf<String>()

    duration?.let { ms ->
        if (ms > 0) {
            parts.add(formatDuration(ms))
        }
    }

    if (width != null && height != null && width > 0 && height > 0) {
        parts.add("${width}×${height}")
    }

    if (hasGainAdjusted) {
        parts.add("GAIN")
    }

    bitrate?.let { bps ->
        if (bps > 0) {
            val mbps = bps / 1_000_000.0
            parts.add(if (mbps >= 1.0) "%.1fMbps".format(mbps) else "%.0fKbps".format(bps / 1000.0))
        }
    }

    if (extension.isNotEmpty()) {
        parts.add(extension.uppercase())
    }

    return parts.joinToString(" · ")
}

internal fun buildMetadataLineWithoutExtension(
    duration: Long?,
    width: Int?,
    height: Int?,
    bitrate: Long?,
    hasGainAdjusted: Boolean = false
): String {
    val parts = mutableListOf<String>()

    duration?.let { ms ->
        if (ms > 0) {
            parts.add(formatDuration(ms))
        }
    }

    if (width != null && height != null && width > 0 && height > 0) {
        parts.add("${width}×${height}")
    }

    if (hasGainAdjusted) {
        parts.add("GAIN")
    }

    bitrate?.let { bps ->
        if (bps > 0) {
            val mbps = bps / 1_000_000.0
            parts.add(if (mbps >= 1.0) "%.1fMbps".format(mbps) else "%.0fKbps".format(bps / 1000.0))
        }
    }

    return parts.joinToString(" · ")
}
