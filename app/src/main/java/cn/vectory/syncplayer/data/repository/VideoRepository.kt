package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val videoItemDao: VideoItemDao
) {

    fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>> =
        videoItemDao.getItemsInFolder(folderPath)

    fun getSubFolders(folderPath: String): Flow<List<String>> =
        videoItemDao.getSubFolders(folderPath)

    suspend fun getById(id: Long): VideoItemEntity? = videoItemDao.getById(id)

    suspend fun getByRelativePath(relativePath: String): VideoItemEntity? =
        videoItemDao.getByRelativePath(relativePath)

    suspend fun getAll(): List<VideoItemEntity> = videoItemDao.getAll()

    suspend fun upsert(item: VideoItemEntity): Long = videoItemDao.upsert(item)

    suspend fun upsertAll(items: List<VideoItemEntity>) = videoItemDao.upsertAll(items)

    suspend fun deleteByRelativePath(relativePath: String) =
        videoItemDao.deleteByRelativePath(relativePath)

    suspend fun deleteById(id: Long) = videoItemDao.deleteById(id)

    suspend fun deleteAll() = videoItemDao.deleteAll()
}
