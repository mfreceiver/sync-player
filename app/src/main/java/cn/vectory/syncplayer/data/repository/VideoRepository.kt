package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.ChildVideoStat
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

    suspend fun getSubFoldersOnce(folderPath: String): List<String> =
        videoItemDao.getSubFoldersOnce(folderPath)

    suspend fun getById(id: Long): VideoItemEntity? = videoItemDao.getById(id)

    suspend fun getByRelativePath(relativePath: String): VideoItemEntity? =
        videoItemDao.getByRelativePath(relativePath)

    suspend fun getByRelativePathAndRelationId(relativePath: String, syncRelationId: Long): VideoItemEntity? =
        videoItemDao.getByRelativePathAndRelationId(relativePath, syncRelationId)

    suspend fun getAll(): List<VideoItemEntity> = videoItemDao.getAll()

    suspend fun getBySyncRelationId(relationId: Long): List<VideoItemEntity> =
        videoItemDao.getBySyncRelationId(relationId)

    suspend fun upsert(item: VideoItemEntity): Long = videoItemDao.upsert(item)

    suspend fun upsertAll(items: List<VideoItemEntity>) = videoItemDao.upsertAll(items)

    suspend fun deleteByRelativePath(relativePath: String) =
        videoItemDao.deleteByRelativePath(relativePath)

    suspend fun deleteByRelativePathAndRelationId(relativePath: String, syncRelationId: Long) =
        videoItemDao.deleteByRelativePathAndRelationId(relativePath, syncRelationId)

    suspend fun deleteById(id: Long) = videoItemDao.deleteById(id)

    suspend fun deleteAll() = videoItemDao.deleteAll()

    suspend fun deleteByFolderPath(folderPath: String) =
        videoItemDao.deleteByFolderPath(folderPath)

    suspend fun getAllIdsSorted(): List<Long> = videoItemDao.getAllIdsSorted()

    fun searchByFileName(query: String): Flow<List<VideoItemEntity>> =
        videoItemDao.searchByFileName(query)

    fun searchByFileNameInFolder(query: String, folder: String): Flow<List<VideoItemEntity>> =
        if (folder.isEmpty()) videoItemDao.searchByFileName(query)
        else videoItemDao.searchByFileNameInFolder(query, folder)

    fun getAllRelativePaths(): Flow<List<String>> =
        videoItemDao.getAllRelativePaths()

    fun getAllParentPaths(): Flow<List<String>> =
        videoItemDao.getAllParentPaths()

    fun getParentPathsInFolder(folder: String): Flow<List<String>> =
        if (folder.isEmpty()) videoItemDao.getAllParentPaths()
        else videoItemDao.getParentPathsInFolder(folder)

    suspend fun getChildVideoStats(folderPath: String): List<ChildVideoStat> =
        videoItemDao.getChildVideoStats(folderPath)

    // New helper APIs for phase 2/3
    suspend fun getRecursiveFolderStats(folderPath: String) = videoItemDao.getRecursiveFolderStats(folderPath)

    fun getAllItemsFlow(): Flow<List<VideoItemEntity>> = videoItemDao.getAllItemsFlow()

    suspend fun getTotalItemCount(): Int = videoItemDao.getTotalItemCount()

    suspend fun getAllRelativePathsList(): List<String> = videoItemDao.getAllRelativePathsList()

    suspend fun updateStar(id: Long, star: Int) = videoItemDao.updateStar(id, star)

    suspend fun updateStarByPath(relativePath: String, star: Int) = videoItemDao.updateStarByPath(relativePath, star)

    suspend fun getFlatItemsPaged(limit: Int, offset: Int): List<VideoItemEntity> =
        videoItemDao.getFlatItemsPaged(limit, offset)

    fun getFlatItemsFlow(): Flow<List<VideoItemEntity>> = videoItemDao.getFlatItemsFlow()

    suspend fun getDirectChildThumbnails(folderPath: String, limit: Int = 4): List<String> =
        videoItemDao.getDirectChildThumbnails(folderPath, limit)

    suspend fun getDirectChildThumbnailsBatch(parentFolder: String): List<VideoItemDao.FolderThumbnailRow> =
        videoItemDao.getDirectChildThumbnailsBatch(parentFolder)
}
