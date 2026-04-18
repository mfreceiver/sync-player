package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoItemDao {
    @Query("""
        SELECT * FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN relativePath NOT LIKE '%/%'
            ELSE relativePath LIKE :folderPath || '/%'
                 AND SUBSTR(relativePath, LENGTH(:folderPath) + 2) NOT LIKE '%/%'
        END
    """)
    fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>>

    @Query("""
        SELECT DISTINCT SUBSTR(
            CASE WHEN :folderPath = '' THEN relativePath
            ELSE SUBSTR(relativePath, LENGTH(:folderPath) + 2)
            END,
            1,
            INSTR(
                CASE WHEN :folderPath = '' THEN relativePath
                ELSE SUBSTR(relativePath, LENGTH(:folderPath) + 2)
                END || '/',
                '/'
            ) - 1
        )
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN relativePath LIKE '%/%'
            ELSE relativePath LIKE :folderPath || '/%'
                 AND SUBSTR(relativePath, LENGTH(:folderPath) + 2) LIKE '%/%'
        END
    """)
    fun getSubFolders(folderPath: String): Flow<List<String>>

    @Query("SELECT * FROM video_item WHERE id = :id")
    suspend fun getById(id: Long): VideoItemEntity?

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

    @Query("SELECT DISTINCT relativePath FROM video_item")
    fun getAllRelativePaths(): Flow<List<String>>

    // Recursive folder statistics for requirement 1 (all files under a folder path)
    data class RecursiveFolderStat(
        val totalFileCount: Int,
        val totalVideoCount: Int,
        val totalSize: Long
    )

    @Query("""
        SELECT 
            COUNT(*) as totalFileCount,
            SUM(CASE WHEN fileName LIKE '%.mp4' OR fileName LIKE '%.mkv' OR fileName LIKE '%.avi' 
                OR fileName LIKE '%.mov' OR fileName LIKE '%.wmv' OR fileName LIKE '%.flv' 
                OR fileName LIKE '%.webm' OR fileName LIKE '%.ts' OR fileName LIKE '%.m4v' 
                OR fileName LIKE '%.3gp' OR fileName LIKE '%.rmvb' OR fileName LIKE '%.rm' THEN 1 ELSE 0 END) as totalVideoCount,
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
        WHERE CASE
            WHEN :folderPath = '' THEN
                LENGTH(vi.relativePath) - LENGTH(REPLACE(vi.relativePath, '/', '')) = 1
            ELSE
                vi.relativePath LIKE :folderPath || '/%'
                AND LENGTH(SUBSTR(vi.relativePath, LENGTH(:folderPath) + 2))
                    - LENGTH(REPLACE(SUBSTR(vi.relativePath, LENGTH(:folderPath) + 2), '/', '')) = 1
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
}
