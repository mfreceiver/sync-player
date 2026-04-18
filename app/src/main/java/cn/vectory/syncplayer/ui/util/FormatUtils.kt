package cn.vectory.syncplayer.ui.util

import java.text.Collator
import java.util.Locale

private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
    strength = Collator.PRIMARY
    decomposition = Collator.CANONICAL_DECOMPOSITION
}

internal val fileNameComparator: Comparator<String> = Comparator { a, b ->
    collator.compare(a, b)
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
