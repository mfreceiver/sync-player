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

    /**
     * One-time migration: move pre-existing user data directories from filesDir root into userdata/.
     * Safe to call multiple times — no-ops if already migrated.
     */
    fun migrateToUserdataLayout() {
        val migrated = File(context.filesDir, MIGRATION_MARKER)
        if (migrated.exists()) return

        val userdataDir = userdataRoot // triggers mkdirs
        val systemDirNames = setOf(USERDATA_DIR, THUMBNAILS_DIR, DEBUG_LOGS_DIR)

        context.filesDir.listFiles()
            ?.filter { it.isDirectory && it.name !in systemDirNames }
            ?.forEach { dir ->
                val target = File(userdataDir, dir.name)
                if (!target.exists()) {
                    dir.renameTo(target)
                }
            }

        migrated.createNewFile()
    }

    companion object {
        const val USERDATA_DIR = "userdata"
        const val THUMBNAILS_DIR = "thumbnails"
        const val DEBUG_LOGS_DIR = "debug-logs"
        private const val MIGRATION_MARKER = ".userdata_migrated"
    }
}
