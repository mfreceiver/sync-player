package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.SyncLogDao
import cn.vectory.syncplayer.data.local.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncLogRepository @Inject constructor(
    private val syncLogDao: SyncLogDao
) {
    suspend fun log(action: String, relativePath: String, status: String, dryRun: Boolean = false, fileSize: Long? = null, errorMessage: String? = null) {
        syncLogDao.insert(SyncLogEntity(
            dryRun = dryRun,
            action = action,
            relativePath = relativePath,
            fileSize = fileSize,
            status = status,
            errorMessage = errorMessage
        ))
    }

    /**
     * P2 扫描日志批量通道：透传既有 [SyncLogDao.insertAll]。
     * 行内容/字段与 [log] 完全一致（同一 [SyncLogEntity] 形状），
     * 仅传输批量化；列表顺序即落库顺序。空列表 no-op。
     */
    suspend fun logAll(entries: List<SyncLogEntity>) {
        if (entries.isEmpty()) return
        syncLogDao.insertAll(entries)
    }

    fun getRecentFlow(limit: Int = 200): Flow<List<SyncLogEntity>> = syncLogDao.getRecentFlow(limit)

    suspend fun clearOldLogs() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        syncLogDao.deleteOlderThan(cutoff)
    }
}
