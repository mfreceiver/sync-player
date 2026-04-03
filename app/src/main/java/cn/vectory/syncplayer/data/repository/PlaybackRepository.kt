package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.PlaybackRecordDao
import cn.vectory.syncplayer.data.local.entity.PlaybackRecordEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackRepository @Inject constructor(
    private val playbackRecordDao: PlaybackRecordDao
) {

    suspend fun getByVideoId(videoItemId: Long): PlaybackRecordEntity? =
        playbackRecordDao.getByVideoId(videoItemId)

    fun getByVideoIdFlow(videoItemId: Long): Flow<PlaybackRecordEntity?> =
        playbackRecordDao.getByVideoIdFlow(videoItemId)

    fun getAllFlow(): Flow<List<PlaybackRecordEntity>> = playbackRecordDao.getAllFlow()

    fun getFavoritesFlow(): Flow<List<PlaybackRecordEntity>> = playbackRecordDao.getFavoritesFlow()

    suspend fun getFavorites(): List<PlaybackRecordEntity> = playbackRecordDao.getFavorites()

    suspend fun upsert(record: PlaybackRecordEntity) = playbackRecordDao.upsert(record)

    suspend fun saveProgress(videoItemId: Long, position: Long, duration: Long) {
        val existing = playbackRecordDao.getByVideoId(videoItemId)
        val isCompleted = duration > 0 && position >= (duration * 0.9)
        if (existing != null) {
            playbackRecordDao.upsert(
                existing.copy(
                    position = position,
                    duration = duration,
                    lastPlayedTime = System.currentTimeMillis(),
                    isCompleted = isCompleted
                )
            )
        } else {
            playbackRecordDao.upsert(
                PlaybackRecordEntity(
                    videoItemId = videoItemId,
                    position = position,
                    duration = duration,
                    lastPlayedTime = System.currentTimeMillis(),
                    isCompleted = isCompleted,
                    isFavorite = false
                )
            )
        }
    }

    suspend fun toggleFavorite(videoItemId: Long) {
        val existing = playbackRecordDao.getByVideoId(videoItemId)
        if (existing != null) {
            playbackRecordDao.updateFavorite(videoItemId, !existing.isFavorite)
        } else {
            playbackRecordDao.upsert(
                PlaybackRecordEntity(
                    videoItemId = videoItemId,
                    position = 0,
                    duration = 0,
                    lastPlayedTime = System.currentTimeMillis(),
                    isCompleted = false,
                    isFavorite = true
                )
            )
        }
    }

    suspend fun setFavorite(videoItemId: Long, favorite: Boolean) {
        playbackRecordDao.updateFavorite(videoItemId, favorite)
    }

    suspend fun deleteByVideoId(videoItemId: Long) =
        playbackRecordDao.deleteByVideoId(videoItemId)
}
