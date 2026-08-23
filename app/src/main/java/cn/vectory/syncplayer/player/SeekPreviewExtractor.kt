package cn.vectory.syncplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class SeekPreviewExtractor(private val context: Context) {

    private companion object {
        // Cache budget in KB. Each scaled frame is up to MAX_WIDTH wide; a
        // 180x320 ARGB_8888 bitmap is ~225 KB. A 4 MB cap holds ~18 frames of
        // that size (more for smaller frames) — enough to cover a typical seek
        // scrub without letting high-resolution frames balloon memory usage.
        const val CACHE_MAX_SIZE_KB = 4 * 1024
        const val MAX_WIDTH = 180
        const val POSITION_ROUNDING_MS = 500L
    }

    private data class CacheKey(
        val uri: String,
        val roundedPositionMs: Long,
    )

    private val cache = object : LruCache<CacheKey, Bitmap>(CACHE_MAX_SIZE_KB) {
        // Charge the cache by bitmap footprint (KB) rather than per-entry count,
        // so a few large frames can't blow the heap under maxSize=30 entries.
        override fun sizeOf(key: CacheKey, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
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

    /**
     * Call when seek gesture begins.
     *
     * `MediaMetadataRetriever.setDataSource()` blocks on binder + filesystem
     * I/O (notably for encrypted content URIs), so it runs on
     * [Dispatchers.IO]. The lock is held only for the cheap reference swap —
     * never during the blocking I/O — to avoid stalling other callers.
     */
    suspend fun beginSession(uri: Uri) {
        val uriString = uri.toString()
        val needNew: Boolean
        synchronized(retrieverLock) {
            needNew = activeUri != uriString
            // Reset prefetch bookkeeping regardless of whether we swap, so a
            // re-begin on the same uri restarts direction tracking cleanly.
            lastExtractedPositionMs = -1L
            lastDirection = 0
        }
        if (!needNew) return

        // Blocking: do this off the calling (often main) thread.
        val newRetriever = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().apply { setDataSource(context, uri) }
        }

        // Swap under the lock; defer the previous retriever's release so we
        // don't hold the lock during native teardown.
        val toRelease: MediaMetadataRetriever? = synchronized(retrieverLock) {
            val prev = activeRetriever
            activeRetriever = newRetriever
            activeUri = uriString
            prev
        }
        withContext(Dispatchers.IO) {
            toRelease?.release()
        }
    }

    /** Call when seek gesture ends. */
    suspend fun endSession() {
        val toRelease: MediaMetadataRetriever? = synchronized(retrieverLock) {
            val prev = activeRetriever
            activeRetriever = null
            activeUri = null
            prev
        }
        prefetchJob?.cancel()
        prefetchJob = null
        // release() can block on native teardown; keep it off the caller's
        // (often main) thread and outside the lock.
        withContext(Dispatchers.IO) {
            toRelease?.release()
        }
    }

    /**
     * Final teardown. Synchronous — called from a non-suspend `onDispose`.
     * `release()` on a retriever is much cheaper than `setDataSource` (no fresh
     * binder/IO), so the dispose path's blocking risk is minimal; the
     * alternative (fire-and-forget on a cancelled scope) would leak the
     * retriever when [prefetchScope] is torn down below.
     */
    fun release() {
        val toRelease: MediaMetadataRetriever? = synchronized(retrieverLock) {
            val prev = activeRetriever
            activeRetriever = null
            activeUri = null
            prev
        }
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchScope.cancel()
        cache.evictAll()
        toRelease?.release()
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
            retriever.setDataSource(context, uri.toUri())
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
        return bitmap.scale(MAX_WIDTH, height)
    }
}
