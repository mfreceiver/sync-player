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
 * ├── userdata/        ← Synced media ([MediaFileStore] owns the bucket layout inside)
 * ├── debug-logs/      ← Debug session logs (system)
 * └── sync-player.db   ← Room database (system)
 * ```
 */
@Singleton
class StoragePaths @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Root for all synced media; [MediaFileStore] owns the layout inside it. */
    val userdataRoot: File
        get() = File(context.filesDir, USERDATA_DIR).also { it.mkdirs() }

    /** Directory for debug log files. Self-healing: auto-creates if deleted. */
    val debugLogsDir: File
        get() = File(context.filesDir, DEBUG_LOGS_DIR).also { it.mkdirs() }

    /**
     * v2 双卷：外部卷数据根位于 getExternalFilesDir 对应目录下的
     * `userdata-v2/<datasetId>/`（relPath 由 [MediaPaths.externalRootRel] 表达，
     * [cn.vectory.syncplayer.storage.volume.FileVolume] 以 relPath 前缀使用）；
     * 此 helper 仅供需要 File 的少数调用方使用。
     */
    fun externalUserdataRoot(externalFilesDir: File, datasetId: String): File =
        File(externalFilesDir, MediaPaths.externalRootRel(datasetId))

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
        const val DEBUG_LOGS_DIR = "debug-logs"
    }
}
