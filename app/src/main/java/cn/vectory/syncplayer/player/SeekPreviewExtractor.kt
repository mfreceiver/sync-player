package cn.vectory.syncplayer.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlin.math.roundToLong

class SeekPreviewExtractor {

    private companion object {
        const val CACHE_MAX_SIZE = 20
        const val MAX_WIDTH = 180
        const val POSITION_ROUNDING_MS = 1000L
    }

    private data class CacheKey(
        val filePath: String,
        val roundedPositionMs: Long,
    )

    private val cache = object : LruCache<CacheKey, Bitmap>(CACHE_MAX_SIZE) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int = 1
    }

    private fun roundPosition(positionMs: Long): Long =
        (positionMs / POSITION_ROUNDING_MS) * POSITION_ROUNDING_MS

    suspend fun extractFrameAtPosition(filePath: String, positionMs: Long): Bitmap? {
        val roundedPosition = roundPosition(positionMs)
        val key = CacheKey(filePath, roundedPosition)

        cache.get(key)?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val raw = retriever.getFrameAtTime(
                roundedPosition * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return null

            val scaled = scaleBitmap(raw)
            if (scaled !== raw) raw.recycle()

            cache.put(key, scaled)
            scaled
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_WIDTH) return bitmap
        val ratio = MAX_WIDTH.toFloat() / bitmap.width
        val height = (bitmap.height * ratio).roundToLong().toInt()
        return Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, height, true)
    }
}
