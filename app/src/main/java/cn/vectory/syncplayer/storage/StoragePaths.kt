package cn.vectory.syncplayer.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized storage path management.
 *
 * Directory layout:
 * ```
 * filesDir/
 * ├── userdata/        ← User-visible data (synced videos, manually created folders)
 * ├── thumbnails/      ← Auto-generated video thumbnails (system)
 * ├── debug-logs/      ← Debug session logs (system)
 * └── sync-player.db   ← Room database (system)
 * ```
 *
 * System directories auto-recreate on access.
 * User data is isolated under [userdataRoot] to prevent accidental deletion of system data.
 */
@Singleton
class StoragePaths @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Root for all user-visible data: synced files and manually created folders. */
    val userdataRoot: File
        get() = File(context.filesDir, USERDATA_DIR).also { it.mkdirs() }

    /** Directory for auto-generated video thumbnails. Self-healing: auto-creates if deleted. */
    val thumbnailsDir: File
        get() = File(context.filesDir, THUMBNAILS_DIR).also { it.mkdirs() }

    /** Directory for debug log files. Self-healing: auto-creates if deleted. */
    val debugLogsDir: File
        get() = File(context.filesDir, DEBUG_LOGS_DIR).also { it.mkdirs() }

    /** Resolve a relative path (e.g. "movies/action/video.mp4") to an absolute file under userdata. */
    fun resolveUserData(relativePath: String): File {
        val trimmed = relativePath.trimStart('/')
        return if (trimmed.isEmpty()) userdataRoot else File(userdataRoot, trimmed)
    }

    fun toLocalRelativePath(basePath: String, remoteRelPath: String): String {
        return if (basePath.isNotEmpty()) "$basePath/$remoteRelPath" else remoteRelPath
    }

    fun toPathUnderBase(basePath: String, localRelPath: String): String {
        return if (basePath.isNotEmpty() && localRelPath.startsWith("$basePath/")) {
            localRelPath.removePrefix("$basePath/")
        } else {
            localRelPath
        }
    }

    // --- Safe path API (canonical confinement) ---

    /**
     * Resolve [relativePath] under [userdataRoot], throwing if the resolved location
     * escapes userdata via traversal (..), absolute paths, or symlinks.
     *
     * Normalizes the path (collapses .. and .), then verifies the canonical result
     * is located at or under [userdataRoot]. Throws [SecurityException] otherwise.
     *
     * Empty/blank input returns [userdataRoot].
     */
    fun safeResolveUserData(relativePath: String): File {
        val root = userdataRoot.canonicalFile
        if (relativePath.isBlank()) return root
        val resolved = canonicalOrAbsolute(File(root, relativePath.trimStart('/')))
        if (resolved != root && !resolved.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Path escapes userdata root: $relativePath -> ${resolved.path}")
        }
        return resolved
    }

    /**
     * Validate a sync-relation localBasePath-style relative path. Rejects traversal
     * that escapes root after normalization. Returns normalized POSIX-style relative
     * path on success; throws [SecurityException] otherwise. Empty input returns "".
     */
    fun normalizeAndValidateRelativePath(relativePath: String): String {
        val input = relativePath.trim().trim('/')
        if (input.isEmpty()) return ""
        val root = userdataRoot.canonicalFile
        val resolved = canonicalOrAbsolute(File(root, input))
        if (resolved != root && !resolved.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Path escapes userdata root: $relativePath")
        }
        return resolved.relativeTo(root).path.replace(File.separatorChar, '/')
    }

    /**
     * Returns [file.canonicalFile], falling back to [file.absoluteFile] on [java.io.IOException].
     * Canonical resolution may throw IOException on broken filesystem entries;
     * absolute path is a safe fallback since the starts-with containment check still works.
     */
    private fun canonicalOrAbsolute(file: File): File = try {
        file.canonicalFile
    } catch (_: java.io.IOException) {
        file.absoluteFile
    }

    companion object {
        const val USERDATA_DIR = "userdata"
        const val THUMBNAILS_DIR = "thumbnails"
        const val DEBUG_LOGS_DIR = "debug-logs"
    }
}
