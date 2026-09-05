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

    /**
     * Max UTF-8 byte budget for the complete share leaf name (stem + suffix + extension).
     * Kept below the common NAME_MAX (255) so the budgeted name is creatable on any volume.
     */
    private const val MAX_LEAF_BYTES = 240

    /** Capped extension budget; leaves guaranteed room for the stem and the "-<id>[-n]" suffix. */
    private const val MAX_EXT_BYTES = MAX_LEAF_BYTES / 2

    /** Fixed ASCII fallback extension (4 bytes — always within budget) for over-long extensions. */
    private const val FALLBACK_EXT = ".bin"

    /**
     * Create a collision-free temp file under `share/` for decrypting video [videoId] into,
     * named after [sanitized] with the video id appended so two same-named videos never map
     * to the same path: `stem-<id>.<ext>`, falling back to `stem-<id>-<n>.<ext>` (n = 1, 2, …)
     * while a candidate is already occupied (BUG-017).
     *
     * Uses [File.createNewFile] (atomic create-or-fail) — an existing in-use file is NEVER
     * overwritten or truncated; the next candidate is tried instead. All leftovers remain
     * subject to the TTL sweep ([cleanupOldShareTempFiles]).
     *
     * Byte budget: every candidate leaf name is ≤ [MAX_LEAF_BYTES] UTF-8 bytes. Only the
     * stem is truncated (at a Unicode code point boundary — never splitting a surrogate
     * pair); the full `-<id>[-n]` suffix is always preserved, which guarantees the candidate
     * sequence strictly differs per attempt and therefore converges; the extension is
     * separately capped at [MAX_EXT_BYTES] or replaced by [FALLBACK_EXT].
     *
     * Guard: a single call tries at most 1000 candidates (n = 0..999); exhaustion fails
     * explicitly with [IllegalStateException] (surfaced by `buildShareIntent`'s existing
     * exception boundary as a failed share) rather than looping forever.
     *
     * [sanitized] MUST already be a [sanitizeFileName] output (flat, non-empty leaf).
     */
    fun uniqueShareFile(context: Context, sanitized: String, videoId: Long): File {
        val dir = shareDir(context)
        val dot = sanitized.lastIndexOf('.')
        val stem = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val rawExt = if (dot > 0) sanitized.substring(dot) else "" // includes '.'; empty = no ext
        val ext = if (rawExt.isEmpty()) "" else truncateUtf8(rawExt, MAX_EXT_BYTES) ?: FALLBACK_EXT
        val extBytes = ext.toByteArray(Charsets.UTF_8).size
        var n = 0
        while (true) {
            val suffix = if (n == 0) "-$videoId" else "-$videoId-$n" // suffix is never truncated
            val stemBudget = MAX_LEAF_BYTES - extBytes - suffix.toByteArray(Charsets.UTF_8).size
            // Budget cannot fit even the stem's first char (or is non-positive) → fixed ASCII
            // fallback stem ("share", 5 bytes); 5 + suffix + ext ≤ MAX holds for every
            // reachable n (ext ≤ 120, suffix has at most ~20 digits).
            val stemPart = if (stemBudget <= 0) "share" else truncateUtf8(stem, stemBudget) ?: "share"
            val candidate = File(dir, "$stemPart$suffix$ext")
            if (candidate.createNewFile()) return candidate // atomic exclusive create; false = taken
            check(++n < 1_000) {
                "uniqueShareFile exhausted 1000 candidate attempts for id=$videoId"
            }
        }
    }

    /**
     * Truncate [s] to at most [maxBytes] UTF-8 bytes, cutting only at a Unicode code point
     * boundary (never splitting a surrogate pair). Returns null when the budget cannot fit
     * the first code point (or is non-positive) — callers substitute an ASCII fallback.
     */
    private fun truncateUtf8(s: String, maxBytes: Int): String? {
        if (maxBytes <= 0) return null
        val sb = StringBuilder()
        var used = 0
        var index = 0
        while (index < s.length) {
            val codePoint = s.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val cpBytes = s.substring(index, index + charCount).toByteArray(Charsets.UTF_8).size
            if (used + cpBytes > maxBytes) break
            sb.appendCodePoint(codePoint)
            used += cpBytes
            index += charCount
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
