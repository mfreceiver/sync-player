package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class SeekPreviewExtractor(private val context: Context) {

    private companion object {
        const val CACHE_MAX_SIZE = 30
        const val MAX_WIDTH = 180
        const val POSITION_ROUNDING_MS = 500L
    }

    private data class CacheKey(
        val uri: String,
        val roundedPositionMs: Long,
    )

    private val cache = object : LruCache<CacheKey, Bitmap>(CACHE_MAX_SIZE) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int = 1
        override fun entryRemoved(
            evicted: Boolean,
            key: CacheKey,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (evicted && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    @Volatile private var activeRetriever: MediaMetadataRetriever? = null
    @Volatile private var activeUri: String? = null
    private val retrieverLock = Any()

    @Volatile private var lastExtractedPositionMs: Long = -1L
    @Volatile private var lastDirection: Int = 0
    private var prefetchJob: Job? = null
    private val prefetchScope = CoroutineScope(Dispatchers.IO)

    /** Call when seek gesture begins. */
    fun beginSession(uri: Uri) {
        synchronized(retrieverLock) {
            val uriString = uri.toString()
            if (activeUri != uriString) {
                activeRetriever?.release()
                activeRetriever = MediaMetadataRetriever().apply {
                    setDataSource(context, uri)
                }
                activeUri = uriString
            }
            lastExtractedPositionMs = -1L
            lastDirection = 0
        }
    }

    /** Call when seek gesture ends. */
    fun endSession() {
        synchronized(retrieverLock) {
            activeRetriever?.release()
            activeRetriever = null
            activeUri = null
        }
        prefetchJob?.cancel()
        prefetchJob = null
    }

    /** Final teardown. */
    fun release() {
        endSession()
        prefetchScope.cancel()
        cache.evictAll()
    }

    fun getCachedFrame(uri: String, positionMs: Long): Bitmap? {
        val roundedPosition = roundPosition(positionMs)
        val key = CacheKey(uri, roundedPosition)
        return cache.get(key)
    }

    private fun roundPosition(positionMs: Long): Long =
        (positionMs / POSITION_ROUNDING_MS) * POSITION_ROUNDING_MS

    suspend fun extractFrameAtPosition(uri: String, positionMs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            val roundedPosition = roundPosition(positionMs)
            val key = CacheKey(uri, roundedPosition)

            cache.get(key)?.let {
                updateDirection(roundedPosition)
                return@withContext it
            }

            val retriever = synchronized(retrieverLock) {
                if (activeUri == uri) activeRetriever
                else null
            } ?: return@withContext extractFallback(uri, roundedPosition, key)

            try {
                val raw = retriever.getFrameAtTime(
                    roundedPosition * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: return@withContext null

                val scaled = scaleBitmap(raw)
                if (scaled !== raw) raw.recycle()
                cache.put(key, scaled)
                updateDirection(roundedPosition)
                schedulePrefetch(uri, roundedPosition)
                scaled
            } catch (_: Exception) {
                null
            }
        }

    private fun updateDirection(currentPositionMs: Long) {
        if (lastExtractedPositionMs >= 0) {
            val delta = currentPositionMs.compareTo(lastExtractedPositionMs)
            if (delta != 0) lastDirection = delta
        }
        lastExtractedPositionMs = currentPositionMs
    }

    private fun schedulePrefetch(uri: String, currentPositionMs: Long) {
        prefetchJob?.cancel()
        prefetchJob = prefetchScope.launch {
            val retriever = synchronized(retrieverLock) {
                if (activeUri == uri) activeRetriever
                else null
            } ?: return@launch

            val steps = if (lastDirection >= 0) {
                listOf(POSITION_ROUNDING_MS, POSITION_ROUNDING_MS * 2)
            } else {
                listOf(-POSITION_ROUNDING_MS, -POSITION_ROUNDING_MS * 2)
            }

            for (step in steps) {
                val nextPos = (currentPositionMs + step).coerceAtLeast(0)
                val roundedNext = roundPosition(nextPos)
                val nextKey = CacheKey(uri, roundedNext)
                if (cache.get(nextKey) != null) continue

                try {
                    val raw = retriever.getFrameAtTime(
                        roundedNext * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: continue
                    val scaled = scaleBitmap(raw)
                    if (scaled !== raw) raw.recycle()
                    cache.put(nextKey, scaled)
                } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    /** Fallback when no active session — creates temporary retriever via content URI. */
    private fun extractFallback(uri: String, roundedMs: Long, key: CacheKey): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            val raw = retriever.getFrameAtTime(
                roundedMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return null
            val scaled = scaleBitmap(raw)
            if (scaled !== raw) raw.recycle()
            cache.put(key, scaled)
            return scaled
        } catch (_: Exception) {
            return null
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
