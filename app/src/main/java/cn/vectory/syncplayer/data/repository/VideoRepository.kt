package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.ChildVideoStat
import cn.vectory.syncplayer.data.local.dao.VideoItemDao.VideoItemWithPlayback
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.FolderStats
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val videoItemDao: VideoItemDao,
    private val appDatabase: AppDatabase,
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

    suspend fun getByPathPrefix(folderPath: String): List<VideoItemEntity> =
        videoItemDao.getByPathPrefix(folderPath)

    suspend fun getByPathPrefixAndRelation(folderPath: String, syncRelationId: Long): List<VideoItemEntity> =
        videoItemDao.getByPathPrefixAndRelation(folderPath, syncRelationId)

    /**
     * Direct-child folder name → has deeper subfolders, computed from a single deep-parentPath query.
     */
    suspend fun getDirectChildFolderHasSubFolders(folderPath: String, directChildNames: List<String>): Map<String, Boolean> {
        if (directChildNames.isEmpty()) return emptyMap()
        val deepParents = videoItemDao.getDeepParentPaths(folderPath)
        val has = mutableMapOf<String, Boolean>().apply {
            directChildNames.forEach { put(it, false) }
        }
        for (parent in deepParents) {
            val after = if (folderPath.isEmpty()) parent else {
                if (!parent.startsWith("$folderPath/")) continue
                parent.substring(folderPath.length + 1)
            }
            val directChild = after.substringBefore('/')
            if (directChild.isNotEmpty() && has.containsKey(directChild)) {
                has[directChild] = true
            }
        }
        return has
    }

    suspend fun upsert(item: VideoItemEntity): Long = videoItemDao.upsert(item)

    suspend fun upsertAll(items: List<VideoItemEntity>) = videoItemDao.upsertAll(items)

    suspend fun deleteByRelativePath(relativePath: String) =
        videoItemDao.deleteByRelativePath(relativePath)

    suspend fun deleteByRelativePathAndRelationId(relativePath: String, syncRelationId: Long) =
        videoItemDao.deleteByRelativePathAndRelationId(relativePath, syncRelationId)

    suspend fun deleteById(id: Long) = videoItemDao.deleteById(id)

    /** Atomically remove playback row + video_item row for [id]. */
    suspend fun deleteMediaRecord(id: Long) {
        appDatabase.withTransaction {
            appDatabase.playbackRecordDao().deleteByVideoId(id)
            videoItemDao.deleteById(id)
        }
    }

    suspend fun deleteAll() = videoItemDao.deleteAll()

    suspend fun getAllIdsSorted(): List<Long> = videoItemDao.getAllIdsSorted()

    fun searchByFileName(query: String): Flow<List<VideoItemWithPlayback>> =
        videoItemDao.searchByFileName(query)

    fun searchByFileNameInFolder(query: String, folder: String): Flow<List<VideoItemWithPlayback>> =
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

    suspend fun getRecursiveFolderStats(folderPath: String): FolderStats {
        val daoResult = videoItemDao.getRecursiveFolderStats(folderPath)
        return FolderStats(
            totalFileCount = daoResult.totalFileCount,
            totalVideoCount = daoResult.totalVideoCount,
            totalImageCount = daoResult.totalImageCount,
            totalSize = daoResult.totalSize
        )
    }

    fun getAllItemsFlow(): Flow<List<VideoItemEntity>> = videoItemDao.getAllItemsFlow()

    fun hasAnyItemsFlow(): Flow<Boolean> = videoItemDao.hasAnyItemsFlow()

    suspend fun getTotalItemCount(): Int = videoItemDao.getTotalItemCount()

    suspend fun getAllRelativePathsList(): List<String> = videoItemDao.getAllRelativePathsList()

    suspend fun updateStar(id: Long, star: Int) = videoItemDao.updateStar(id, star)

    suspend fun updateAudioGain(id: Long, audioGainDb: Float?) =
        videoItemDao.updateAudioGain(id, audioGainDb)

    // ------------------------------------------------------------------ A1 按列更新（列所有权）

    /**
     * A1: 下载管线最终元数据列提交（fileSize/remoteModifiedTime/dateSynced）。
     * 返回受影响行数：0 = 行不存在（含并发删除）——调用方按孤儿文件处理，
     * 绝不整行 upsert（会覆盖 star/audioGainDb 或复活被删行）。
     */
    suspend fun updateSyncMetadata(id: Long, fileSize: Long, remoteModifiedTime: Long?, dateSynced: Long): Int =
        videoItemDao.updateSyncMetadata(id, fileSize, remoteModifiedTime, dateSynced)

    /**
     * A1: 视频提取器列提交（duration/width/height/bitrate/frameRate 全列，
     * 显式 null = 清除陈旧值）。返回受影响行数：0 = 行已消失，不复活。
     */
    suspend fun updateVideoMetadata(
        id: Long,
        duration: Long?,
        width: Int?,
        height: Int?,
        bitrate: Long?,
        frameRate: Float?
    ): Int = videoItemDao.updateVideoMetadata(id, duration, width, height, bitrate, frameRate)

    /**
     * A1: 图片提取器列提交（width/height/dateTaken）。返回受影响行数：0 = 行已消失，不复活。
     */
    suspend fun updateImageMetadata(id: Long, width: Int?, height: Int?, dateTaken: Long?): Int =
        videoItemDao.updateImageMetadata(id, width, height, dateTaken)

    fun getFlatItemsFlow(): Flow<List<VideoItemEntity>> = videoItemDao.getFlatItemsFlow()

    suspend fun getFlatItemsFiltered(
        mediaOnly: Boolean,
        unwatchedOnly: Boolean,
        starMode: String,
        starValue: Int,
        sortCol: String
    ): List<VideoItemDao.FlatItemWithPlayback> =
        videoItemDao.getFlatItemsFiltered(
            mediaOnly = if (mediaOnly) 1 else 0,
            unwatchedOnly = if (unwatchedOnly) 1 else 0,
            starMode = starMode,
            starValue = starValue,
            sortCol = sortCol
        )
}
