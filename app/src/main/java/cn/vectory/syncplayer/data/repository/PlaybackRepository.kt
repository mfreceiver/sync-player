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

    fun getTrashedFlow(): Flow<List<PlaybackRecordEntity>> = playbackRecordDao.getTrashedFlow()

    fun getByFolderFlow(folderPath: String): Flow<List<PlaybackRecordEntity>> =
        playbackRecordDao.getByFolderFlow(folderPath)

    suspend fun getTrashed(): List<PlaybackRecordEntity> = playbackRecordDao.getTrashed()

    suspend fun upsert(record: PlaybackRecordEntity) = playbackRecordDao.upsert(record)

    suspend fun saveProgress(videoItemId: Long, position: Long, duration: Long) {
        val existing = playbackRecordDao.getByVideoId(videoItemId)
        val isCompleted = duration > 0 && position >= (duration * 0.95)
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
                    isTrashed = false
                )
            )
        }
    }

    suspend fun toggleTrashed(videoItemId: Long) {
        val existing = playbackRecordDao.getByVideoId(videoItemId)
        if (existing != null) {
            playbackRecordDao.updateTrashed(videoItemId, !existing.isTrashed)
        } else {
            playbackRecordDao.upsert(
                PlaybackRecordEntity(
                    videoItemId = videoItemId,
                    position = 0,
                    duration = 0,
                    lastPlayedTime = System.currentTimeMillis(),
                    isCompleted = false,
                    isTrashed = true
                )
            )
        }
    }

    suspend fun setTrashed(videoItemId: Long, trashed: Boolean) {
        playbackRecordDao.updateTrashed(videoItemId, trashed)
    }

    suspend fun deleteByVideoId(videoItemId: Long) =
        playbackRecordDao.deleteByVideoId(videoItemId)

    suspend fun deleteByFolderPath(folderPath: String) =
        playbackRecordDao.deleteByFolderPath(folderPath)

    // Phase 2: ensure folder has corresponding playback records and mark all as completed/uncompleted
    suspend fun markFolderCompleted(folderPath: String, completed: Boolean) {
        // First ensure all videos in folder have playback records
        val dao = playbackRecordDao
        val videoIdsWithoutRecord = dao.getVideoIdsWithoutRecord(folderPath)
        if (videoIdsWithoutRecord.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val records = videoIdsWithoutRecord.map { videoId ->
                PlaybackRecordEntity(
                    videoItemId = videoId,
                    position = 0,
                    duration = 0,
                    lastPlayedTime = now,
                    isCompleted = completed,
                    isTrashed = false
                )
            }
            dao.upsertAllBatch(records)
        }
        // Then update existing records
        dao.updateCompletedByFolder(folderPath, completed)
    }
}
