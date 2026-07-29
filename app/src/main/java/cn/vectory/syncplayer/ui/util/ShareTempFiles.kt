package cn.vectory.syncplayer.ui.util

import android.content.Context
import java.io.File

/**
 * Helpers for the share-via-FileProvider temp directory (`cacheDir/share/`).
 *
 * Sharing works by decrypting the requested media into a real file under `share/` and exposing
 * it through a [androidx.core.content.FileProvider]. The file is named with the entity's
 * original [VideoItemEntity.fileName] so recipient apps that ignore `ContentResolver.query()`
 * and just read the URI's last path segment still see a sensible name with its extension.
 *
 * Two safety concerns drive this object:
 *  1. **Path traversal** — `fileName` arrives from a remote SFTP server and may contain `..`,
 *     separators, absolute paths, or null bytes. [sanitizeFileName] must reduce any such input
 *     to a flat leaf name before it is concatenated onto `shareDir`.
 *  2. **Disk growth** — every share leaves a temp file behind (we cannot delete it before the
 *     recipient finishes reading it, and we don't get a callback when it does). [cleanupOldShareTempFiles]
 *     sweeps files older than the TTL so `share/` cannot grow unbounded; the TTL is long enough
 *     that the file currently being shared is never touched.
 */
object ShareTempFiles {

    /** Default max age (1 hour) before a leftover share temp file is considered sweepable. */
    const val DEFAULT_MAX_AGE_MS: Long = 60L * 60L * 1000L

    /** Fallback leaf name when the source `fileName` is empty, malicious, or unusable. */
    const val FALLBACK_NAME: String = "video.mp4"

    /**
     * The shared temp directory under [Context.cacheDir]. Created on first access.
     * Never returns the cache root itself — always the `share/` child, matching the
     * `<cache-path path="share/"/>` entry in `res/xml/file_paths.xml`.
     */
    fun shareDir(context: Context): File =
        File(context.cacheDir, "share").apply { mkdirs() }

    /**
     * Delete leftover share temp files older than [maxAgeMs] (based on [File.lastModified]).
     *
     * The TTL must exceed the realistic "share → recipient finishes reading" window so the file
     * currently being handed off is never deleted mid-read. [DEFAULT_MAX_AGE_MS] (1h) is plenty.
     *
     * Failures deleting individual files are swallowed (best-effort sweep); the next sweep retries.
     * `CancellationException` is N/A here — this is a plain (non-suspending) file walk.
     */
    fun cleanupOldShareTempFiles(
        context: Context,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val dir = shareDir(context)
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (f in files) {
            if (!f.isFile) continue
            val age = now - f.lastModified()
            if (age >= maxAgeMs) {
                if (f.delete()) deleted++
            }
        }
        return deleted
    }

    /**
     * Reduce an untrusted remote [name] to a flat, non-empty leaf filename safe to join onto
     * [shareDir] with `File(shareDir, ...)`.
     *
     * Defense layers (apply in order; first failure → [FALLBACK_NAME]):
     *  1. Reject null bytes outright (defends against NUL-truncation tricks on any FS layer).
     *  2. Take the last segment after `/` AND `\` (handles `..\evil`, `/etc/passwd`, `C:\x`).
     *  3. Trim whitespace, then strip leading dots (defeats `..`, `...`, leading-dot hidden names).
     *  4. Reject empty / all-dot / parent-traversal remnants.
     *  5. Defense-in-depth: reject any remaining separator or NUL after cleaning.
     *
     * URL-encoded forms (`%2F`, `%2E`) are NOT decoded, so `%2e%2e%2fevil.mp4` survives as a
     * single leaf — it never becomes a path. This is intentional: no consumer in this path
     * URL-decodes filenames.
     *
     * Returns [FALLBACK_NAME] for any input that fails any layer, so the caller can never produce
     * an empty filename or escape [shareDir].
     */
    fun sanitizeFileName(name: String): String {
        // Layer 1: NUL bytes are never acceptable in a filename on any FS.
        if ('\u0000' in name) return FALLBACK_NAME

        // Layer 2: collapse to the last path segment (both UNIX and Windows separators).
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')

        // Layer 3: trim surrounding whitespace, then strip leading dots (handles `..`, `...`, etc.).
        val trimmed = lastSegment.trim().trimStart('.')

        // Layer 4: reject empty, all-dot, or traversal remnants.
        if (trimmed.isEmpty()) return FALLBACK_NAME
        if (trimmed == "..") return FALLBACK_NAME
        if (trimmed.all { it == '.' }) return FALLBACK_NAME

        // Layer 5: defense-in-depth — no separators or NUL may remain after the above.
        if ('/' in trimmed || '\\' in trimmed || '\u0000' in trimmed) return FALLBACK_NAME

        return trimmed
    }
}
