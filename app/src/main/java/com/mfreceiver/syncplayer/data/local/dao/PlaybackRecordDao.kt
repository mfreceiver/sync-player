package com.mfreceiver.syncplayer.data.local.dao

import androidx.room.*
import com.mfreceiver.syncplayer.data.local.entity.PlaybackRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackRecordDao {
    @Query("SELECT * FROM playback_record WHERE videoItemId = :videoItemId")
    suspend fun getByVideoId(videoItemId: Long): PlaybackRecordEntity?

    @Query("SELECT * FROM playback_record WHERE videoItemId = :videoItemId")
    fun getByVideoIdFlow(videoItemId: Long): Flow<PlaybackRecordEntity?>

    @Query("SELECT * FROM playback_record ORDER BY lastPlayedTime DESC")
    fun getAllFlow(): Flow<List<PlaybackRecordEntity>>

    @Query("SELECT * FROM playback_record WHERE isFavorite = 1 ORDER BY lastPlayedTime DESC")
    fun getFavoritesFlow(): Flow<List<PlaybackRecordEntity>>

    @Upsert
    suspend fun upsert(record: PlaybackRecordEntity)

    @Query("DELETE FROM playback_record WHERE videoItemId = :videoItemId")
    suspend fun deleteByVideoId(videoItemId: Long)

    @Query("""
        UPDATE playback_record 
        SET isCompleted = :completed 
        WHERE videoItemId = :videoItemId
    """)
    suspend fun updateCompleted(videoItemId: Long, completed: Boolean)

    @Query("""
        UPDATE playback_record 
        SET isFavorite = :favorite 
        WHERE videoItemId = :videoItemId
    """)
    suspend fun updateFavorite(videoItemId: Long, favorite: Boolean)
}
