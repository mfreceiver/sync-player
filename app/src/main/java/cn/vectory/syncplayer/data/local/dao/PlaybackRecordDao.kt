package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackRecordDao {
    @Query("SELECT * FROM playback_record WHERE videoItemId = :videoItemId")
    suspend fun getByVideoId(videoItemId: Long): PlaybackRecordEntity?

    @Query("SELECT * FROM playback_record WHERE videoItemId = :videoItemId")
    fun getByVideoIdFlow(videoItemId: Long): Flow<PlaybackRecordEntity?>

    @Query("SELECT * FROM playback_record ORDER BY lastPlayedTime DESC")
    fun getAllFlow(): Flow<List<PlaybackRecordEntity>>

    @Query("SELECT * FROM playback_record WHERE isTrashed = 1 ORDER BY lastPlayedTime DESC")
    fun getTrashedFlow(): Flow<List<PlaybackRecordEntity>>

    @Query("SELECT * FROM playback_record WHERE isTrashed = 1")
    suspend fun getTrashed(): List<PlaybackRecordEntity>

    @Upsert
    suspend fun upsert(record: PlaybackRecordEntity)

    @Query("DELETE FROM playback_record WHERE videoItemId = :videoItemId")
    suspend fun deleteByVideoId(videoItemId: Long)

    @Query("""
        DELETE FROM playback_record WHERE videoItemId IN (
            SELECT id FROM video_item WHERE relativePath LIKE :folderPath || '/%'
        )
    """)
    suspend fun deleteByFolderPath(folderPath: String)

    @Query("""
        UPDATE playback_record 
        SET isCompleted = :completed 
        WHERE videoItemId = :videoItemId
    """)
    suspend fun updateCompleted(videoItemId: Long, completed: Boolean)

    @Query("""
        UPDATE playback_record 
        SET isTrashed = :trashed
        WHERE videoItemId = :videoItemId
    """)
    suspend fun updateTrashed(videoItemId: Long, trashed: Boolean)

    @Query("""
        SELECT pr.* FROM playback_record pr
        INNER JOIN video_item vi ON pr.videoItemId = vi.id
        WHERE CASE
            WHEN :folderPath = '' THEN vi.relativePath NOT LIKE '%/%'
            ELSE vi.relativePath LIKE :folderPath || '/%'
                 AND SUBSTR(vi.relativePath, LENGTH(:folderPath) + 2) NOT LIKE '%/%'
        END
    """)
    fun getByFolderFlow(folderPath: String): Flow<List<PlaybackRecordEntity>>

    // Batch mark as completed (mark read) for all videos in a folder recursively
    @Query("""
        UPDATE playback_record SET isCompleted = :completed
        WHERE videoItemId IN (
            SELECT id FROM video_item WHERE relativePath LIKE :folderPath || '/%'
        )
    """)
    suspend fun updateCompletedByFolder(folderPath: String, completed: Boolean)

    // Get video IDs in a folder that don't have playback records
    @Query("""
        SELECT vi.id FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE vi.relativePath LIKE :folderPath || '/%' AND pr.id IS NULL
    """)
    suspend fun getVideoIdsWithoutRecord(folderPath: String): List<Long>
}
