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

    @Query("DELETE FROM video_item WHERE relativePath LIKE :folderPath || '/%'")
    suspend fun deleteByFolderPath(folderPath: String)

    @Query("SELECT id FROM video_item ORDER BY fileName ASC")
    suspend fun getAllIdsSorted(): List<Long>

    @Query("SELECT * FROM video_item WHERE fileName LIKE '%' || :query || '%' COLLATE NOCASE")
    fun searchByFileName(query: String): Flow<List<VideoItemEntity>>

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM video_item WHERE fileName LIKE '%' || :query || '%' COLLATE NOCASE")
    fun searchByFileNameSync(query: String): List<VideoItemEntity>

    @Query("""
        SELECT * FROM video_item 
        WHERE fileName LIKE '%' || :query || '%' COLLATE NOCASE 
        AND (parentPath = :folder OR parentPath LIKE :folder || '/%')
    """)
    fun searchByFileNameInFolder(query: String, folder: String): Flow<List<VideoItemEntity>>

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

    // Get flat items with pagination for file mode
    @Query("SELECT * FROM video_item ORDER BY fileName ASC LIMIT :limit OFFSET :offset")
    suspend fun getFlatItemsPaged(limit: Int, offset: Int): List<VideoItemEntity>

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

    @Query("""
        SELECT thumbnailPath FROM video_item
        WHERE thumbnailPath IS NOT NULL
        AND parentPath = :folderPath
        ORDER BY fileName ASC
        LIMIT :limit
    """)
    suspend fun getDirectChildThumbnails(folderPath: String, limit: Int = 4): List<String>

    data class FolderThumbnailRow(
        val relativePath: String,
        val thumbnailPath: String
    )

    @Query("""
        SELECT relativePath, thumbnailPath FROM video_item
        WHERE thumbnailPath IS NOT NULL
        AND CASE
            WHEN :parentFolder = '' THEN parentPath != '' AND parentPath NOT LIKE '%/%'
            ELSE parentPath LIKE :parentFolder || '/%'
                 AND LENGTH(SUBSTR(parentPath, LENGTH(:parentFolder) + 2))
                    - LENGTH(REPLACE(SUBSTR(parentPath, LENGTH(:parentFolder) + 2), '/', '')) = 0
        END
        ORDER BY relativePath
    """)
    suspend fun getDirectChildThumbnailsBatch(parentFolder: String): List<FolderThumbnailRow>
}
