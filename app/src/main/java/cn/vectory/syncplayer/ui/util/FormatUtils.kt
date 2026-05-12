package cn.vectory.syncplayer.ui.util

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

private fun charCategoryPriority(c: Char): Int = when {
    c in 'a'..'z' || c in 'A'..'Z' -> 0
    c.code < 128 -> 1
    else -> 2
}

private fun nonNumericCompare(a: String, b: String): Int {
    val minLen = minOf(a.length, b.length)
    for (i in 0 until minLen) {
        val catA = charCategoryPriority(a[i])
        val catB = charCategoryPriority(b[i])
        if (catA != catB) return catA - catB
        if (a[i] != b[i]) {
            val aLower = a[i].lowercaseChar()
            val bLower = b[i].lowercaseChar()
            if (aLower != bLower) return aLower - bLower
            return b[i] - a[i]
        }
    }
    return a.length - b.length
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
            else -> nonNumericCompare(tA.value, tB.value)
        }
        if (cmp != 0) return@Comparator cmp
    }
    tokensA.size - tokensB.size
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups.coerceAtMost(units.size - 1)])
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

internal fun buildImageMetadataLine(
    width: Int?,
    height: Int?,
    dateTaken: Long?,
    fileSize: Long = 0
): String {
    val parts = mutableListOf<String>()
    if (width != null && height != null && width > 0 && height > 0) {
        parts.add("${width}×${height}")
    }
    dateTaken?.let { dt ->
        if (dt > 0) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add(sdf.format(java.util.Date(dt)))
        }
    }
    if (fileSize > 0) {
        parts.add(formatFileSize(fileSize))
    }
    return parts.joinToString(" · ")
}

internal fun buildImageMetadataLineWithExtension(
    width: Int?,
    height: Int?,
    dateTaken: Long?,
    fileSize: Long = 0,
    extension: String = ""
): String {
    val parts = mutableListOf<String>()
    if (width != null && height != null && width > 0 && height > 0) {
        parts.add("${width}×${height}")
    }
    dateTaken?.let { dt ->
        if (dt > 0) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add(sdf.format(java.util.Date(dt)))
        }
    }
    if (fileSize > 0) {
        parts.add(formatFileSize(fileSize))
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
