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

    fun getRecentFlow(limit: Int = 200): Flow<List<SyncLogEntity>> = syncLogDao.getSince(0)

    suspend fun clearOldLogs() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        syncLogDao.deleteOlderThan(cutoff)
    }
}
