package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_item WHERE parentPath = :folderPath")
    fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>>

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM video_item WHERE parentPath = :folderPath ORDER BY fileName COLLATE NOCASE ASC")
    fun getItemsInFolderSync(folderPath: String): List<VideoItemEntity>

    @Query("""
        SELECT DISTINCT SUBSTR(
            CASE WHEN :folderPath = '' THEN parentPath
            ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
            END,
            1,
            INSTR(
                CASE WHEN :folderPath = '' THEN parentPath
                ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                END || '/',
                '/'
            ) - 1
        )
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN parentPath != ''
            ELSE parentPath LIKE :folderPath || '/%'
        END
    """)
    fun getSubFolders(folderPath: String): Flow<List<String>>

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("""
        SELECT DISTINCT SUBSTR(
            CASE WHEN :folderPath = '' THEN parentPath
            ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
            END,
            1,
            INSTR(
                CASE WHEN :folderPath = '' THEN parentPath
                ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                END || '/',
                '/'
            ) - 1
        )
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN parentPath != ''
            ELSE parentPath LIKE :folderPath || '/%'
        END
    """)
    fun getSubFoldersSync(folderPath: String): List<String>

    @Query("""
        SELECT DISTINCT SUBSTR(
            CASE WHEN :folderPath = '' THEN parentPath
            ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
            END,
            1,
            INSTR(
                CASE WHEN :folderPath = '' THEN parentPath
                ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                END || '/',
                '/'
            ) - 1
        )
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN parentPath != ''
            ELSE parentPath LIKE :folderPath || '/%'
        END
    """)
    suspend fun getSubFoldersOnce(folderPath: String): List<String>

    @Query("SELECT * FROM video_item WHERE id = :id")
    suspend fun getById(id: Long): VideoItemEntity?

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM video_item WHERE id = :id")
    fun getByIdSync(id: Long): VideoItemEntity?

    @Query("SELECT * FROM video_item WHERE relativePath = :relativePath")
    suspend fun getByRelativePath(relativePath: String): VideoItemEntity?

    @Query("SELECT * FROM video_item")
    suspend fun getAll(): List<VideoItemEntity>

    @Query("SELECT * FROM video_item WHERE syncRelationId = :relationId")
    suspend fun getBySyncRelationId(relationId: Long): List<VideoItemEntity>

    @Query("SELECT * FROM video_item WHERE relativePath = :relativePath AND syncRelationId = :syncRelationId")
    suspend fun getByRelativePathAndRelationId(relativePath: String, syncRelationId: Long): VideoItemEntity?

    @Upsert
    suspend fun upsert(item: VideoItemEntity): Long

    @Upsert
    suspend fun upsertAll(items: List<VideoItemEntity>)

    @Query("DELETE FROM video_item WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM video_item WHERE relativePath = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM video_item WHERE relativePath = :relativePath AND syncRelationId = :syncRelationId")
    suspend fun deleteByRelativePathAndRelationId(relativePath: String, syncRelationId: Long)

    @Query("DELETE FROM video_item")
    suspend fun deleteAll()

    /** Items under [folderPath] (exact or descendants). Path-boundary safe (no false prefix match). */
    @Query("""
        SELECT * FROM video_item
        WHERE relativePath = :folderPath
           OR relativePath LIKE :folderPath || '/%'
    """)
    suspend fun getByPathPrefix(folderPath: String): List<VideoItemEntity>

    /** Relation-scoped path prefix query for deleteFolder isolation. */
    @Query("""
        SELECT * FROM video_item
        WHERE syncRelationId = :syncRelationId
          AND (relativePath = :folderPath OR relativePath LIKE :folderPath || '/%')
    """)
    suspend fun getByPathPrefixAndRelation(folderPath: String, syncRelationId: Long): List<VideoItemEntity>

    /**
     * ParentPaths of all descendants under [folderPath] that have at least one more path segment
     * after the direct child. Used to compute folderHasSubDirs in one query.
     */
    @Query("""
        SELECT DISTINCT parentPath FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN parentPath LIKE '%/%'
            ELSE parentPath LIKE :folderPath || '/%/%'
        END
    """)
    suspend fun getDeepParentPaths(folderPath: String): List<String>

    @Query("SELECT id FROM video_item ORDER BY fileName ASC")
    suspend fun getAllIdsSorted(): List<Long>

    data class VideoItemWithPlayback(
        val id: Long,
        val relativePath: String,
        val fileName: String,
        val fileSize: Long,
        val duration: Long?,
        val width: Int?,
        val height: Int?,
        val bitrate: Long?,
        val frameRate: Float?,
        val audioGainDb: Float?,
        val star: Int,
        val mediaType: String,
        val dateTaken: Long?,
        val playbackPosition: Long,
        val playbackDuration: Long,
        val isCompleted: Boolean,
        val isTrashed: Boolean
    )

    @Query("""
        SELECT v.id, v.fileName, v.relativePath, v.fileSize, v.duration, v.width, v.height,
               v.bitrate, v.frameRate, v.audioGainDb, v.star, v.mediaType, v.dateTaken,
               COALESCE(pr.position, 0) as playbackPosition,
               COALESCE(pr.duration, 0) as playbackDuration,
               COALESCE(pr.isCompleted, 0) as isCompleted,
               COALESCE(pr.isTrashed, 0) as isTrashed
        FROM video_item v
        LEFT JOIN playback_record pr ON pr.videoItemId = v.id
        WHERE v.fileName LIKE '%' || :query || '%' COLLATE NOCASE
    """)
    fun searchByFileName(query: String): Flow<List<VideoItemWithPlayback>>

    /** Synchronous variant for binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM video_item WHERE fileName LIKE '%' || :query || '%' COLLATE NOCASE")
    fun searchByFileNameSync(query: String): List<VideoItemEntity>

    @Query("""
        SELECT v.id, v.fileName, v.relativePath, v.fileSize, v.duration, v.width, v.height,
               v.bitrate, v.frameRate, v.audioGainDb, v.star, v.mediaType, v.dateTaken,
               COALESCE(pr.position, 0) as playbackPosition,
               COALESCE(pr.duration, 0) as playbackDuration,
               COALESCE(pr.isCompleted, 0) as isCompleted,
               COALESCE(pr.isTrashed, 0) as isTrashed
        FROM video_item v
        LEFT JOIN playback_record pr ON pr.videoItemId = v.id
        WHERE v.fileName LIKE '%' || :query || '%' COLLATE NOCASE
        AND (v.parentPath = :folder OR v.parentPath LIKE :folder || '/%')
    """)
    fun searchByFileNameInFolder(query: String, folder: String): Flow<List<VideoItemWithPlayback>>

    @Query("SELECT DISTINCT relativePath FROM video_item")
    fun getAllRelativePaths(): Flow<List<String>>

    @Query("SELECT DISTINCT parentPath FROM video_item")
    fun getAllParentPaths(): Flow<List<String>>

    @Query("""
        SELECT DISTINCT parentPath FROM video_item 
        WHERE parentPath = :folder OR parentPath LIKE :folder || '/%'
    """)
    fun getParentPathsInFolder(folder: String): Flow<List<String>>

    // Recursive folder statistics for requirement 1 (all files under a folder path)
    data class RecursiveFolderStat(
        val totalFileCount: Int,
        val totalVideoCount: Int,
        val totalImageCount: Int,
        val totalSize: Long
    )

    @Query("""
        SELECT 
            COUNT(*) as totalFileCount,
            SUM(CASE WHEN mediaType = 'VIDEO' THEN 1 ELSE 0 END) as totalVideoCount,
            SUM(CASE WHEN mediaType = 'IMAGE' THEN 1 ELSE 0 END) as totalImageCount,
            COALESCE(SUM(fileSize), 0) as totalSize
        FROM video_item
        WHERE relativePath LIKE :folderPath || '/%'
    """
    )
    suspend fun getRecursiveFolderStats(folderPath: String): RecursiveFolderStat

    // Flat mode: get all items for file browsing mode
    @Query("SELECT * FROM video_item")
    fun getAllItemsFlow(): Flow<List<VideoItemEntity>>

    // Flat mode: get total count for scrollbar estimation
    @Query("SELECT COUNT(*) FROM video_item")
    suspend fun getTotalItemCount(): Int

    // Get all distinct directory paths for directory tree building
    @Query("SELECT DISTINCT relativePath FROM video_item")
    suspend fun getAllRelativePathsList(): List<String>

    // Update star rating for a video item
    @Query("UPDATE video_item SET star = :star WHERE id = :id")
    suspend fun updateStar(id: Long, star: Int)

    // Update star rating by relative path (for non-DB files that get a record)
    @Query("UPDATE video_item SET star = :star WHERE relativePath = :relativePath")
    suspend fun updateStarByPath(relativePath: String, star: Int)

    @Query("SELECT * FROM video_item ORDER BY fileName ASC")
    fun getFlatItemsFlow(): Flow<List<VideoItemEntity>>

    @Query("""
        SELECT vi.id, vi.relativePath, vi.fileName, vi.audioGainDb,
               pr.position, pr.isCompleted, pr.isTrashed
        FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE vi.mediaType IN ('VIDEO', 'IMAGE')
        AND CASE
            WHEN :folderPath = '' THEN
                vi.parentPath != '' AND vi.parentPath NOT LIKE '%/%'
            ELSE
                vi.parentPath LIKE :folderPath || '/%'
                AND LENGTH(SUBSTR(vi.parentPath, LENGTH(:folderPath) + 2))
                    - LENGTH(REPLACE(SUBSTR(vi.parentPath, LENGTH(:folderPath) + 2), '/', '')) = 0
        END
    """)
    suspend fun getChildVideoStats(folderPath: String): List<ChildVideoStat>

    data class ChildVideoStat(
        val id: Long,
        val relativePath: String,
        val fileName: String,
        val audioGainDb: Float?,
        val position: Long,
        val isCompleted: Boolean,
        val isTrashed: Boolean
    )

    data class FlatItemWithPlayback(
        val id: Long,
        val fileName: String,
        val relativePath: String,
        val fileSize: Long,
        val duration: Long?,
        val width: Int?,
        val height: Int?,
        val bitrate: Long?,
        val frameRate: Float?,
        val audioGainDb: Float?,
        val star: Int,
        val mediaType: String,
        val dateTaken: Long?,
        val playbackPosition: Long,
        val playbackDuration: Long,
        val isCompleted: Boolean,
        val isTrashed: Boolean
    )

    @Query("""
        SELECT vi.id, vi.fileName, vi.relativePath, vi.fileSize, vi.duration, vi.width, vi.height,
               vi.bitrate, vi.frameRate, vi.audioGainDb, vi.star, vi.mediaType, vi.dateTaken,
               COALESCE(pr.position, 0) AS playbackPosition,
               COALESCE(pr.duration, 0) AS playbackDuration,
               COALESCE(pr.isCompleted, 0) AS isCompleted,
               COALESCE(pr.isTrashed, 0) AS isTrashed
        FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE (:mediaOnly = 0 OR vi.mediaType IN ('VIDEO', 'IMAGE'))
          AND (:unwatchedOnly = 0 OR (pr.position IS NULL OR (pr.position = 0 AND COALESCE(pr.duration, 0) = 0 AND COALESCE(pr.isCompleted, 0) = 0)))
          AND (:starMode = 'OFF' OR
               (:starMode = 'EQ' AND vi.star = :starValue) OR
               (:starMode = 'GT' AND vi.star > :starValue) OR
               (:starMode = 'LT' AND vi.star < :starValue))
        ORDER BY
          CASE WHEN :sortCol = 'duration_asc' THEN CASE WHEN vi.mediaType = 'IMAGE' THEN vi.dateTaken ELSE vi.duration END END ASC,
          CASE WHEN :sortCol = 'duration_desc' THEN CASE WHEN vi.mediaType = 'IMAGE' THEN vi.dateTaken ELSE vi.duration END END DESC,
          CASE WHEN :sortCol = 'fileSize_asc' THEN vi.fileSize END ASC,
          CASE WHEN :sortCol = 'fileSize_desc' THEN vi.fileSize END DESC
    """)
    suspend fun getFlatItemsFiltered(
        mediaOnly: Int,
        unwatchedOnly: Int,
        starMode: String,
        starValue: Int,
        sortCol: String
    ): List<FlatItemWithPlayback>
}
