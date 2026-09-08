package cn.vectory.syncplayer.data.repository

import cn.vectory.syncplayer.data.local.dao.SyncRelationDao
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.model.SyncOutcome
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRelationRepository @Inject constructor(
    private val syncRelationDao: SyncRelationDao,
    private val syncSourceDao: SyncSourceDao
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllWithSourceFlow(): Flow<List<SyncRelationWithSource>> {
        return combine(syncRelationDao.getAllFlow(), syncSourceDao.getAllFlow()) { relations, sources ->
            val sourceMap = sources.associateBy { it.id }
            relations.map { entity ->
                val source = sourceMap[entity.syncSourceId]
                SyncRelationWithSource(
                    relation = entity.toDomain(),
                    sourceName = source?.name,
                    sourceProtocol = source?.protocol?.let { SyncProtocol.valueOf(it) } ?: SyncProtocol.SMB
                )
            }
        }
    }

    suspend fun getAllWithSource(): List<SyncRelationWithSource> {
        val relations = syncRelationDao.getAll()
        val sources = syncSourceDao.getAll()
        val sourceMap = sources.associateBy { it.id }
        return relations.map { entity ->
            val source = sourceMap[entity.syncSourceId]
            SyncRelationWithSource(
                relation = entity.toDomain(),
                sourceName = source?.name,
                sourceProtocol = source?.protocol?.let { SyncProtocol.valueOf(it) } ?: SyncProtocol.SMB
            )
        }
    }

    fun getAllFlow(): Flow<List<SyncRelation>> =
        syncRelationDao.getAllFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<SyncRelation> =
        syncRelationDao.getAll().map { it.toDomain() }

    suspend fun getById(id: Long): SyncRelation? =
        syncRelationDao.getById(id)?.toDomain()

    suspend fun getByLocalBasePath(path: String): SyncRelation? =
        syncRelationDao.getByLocalBasePath(path)?.toDomain()

    /**
     * Check for base-path conflicts including exact matches and ancestor/descendant nesting.
     * Returns relations whose [localBasePath] equals [path] or is a parent/child of [path].
     * Excludes the relation identified by [excludeRelationId] (0 = no exclusion).
     */
    suspend fun checkBasePathConflict(path: String, excludeRelationId: Long = 0): List<SyncRelation> {
        val excludePath = if (excludeRelationId > 0) {
            syncRelationDao.getById(excludeRelationId)?.localBasePath ?: ""
        } else ""
        return syncRelationDao.findConflictingBasePaths(path, excludePath).map { it.toDomain() }
    }

    suspend fun getAllBasePaths(): List<String> =
        syncRelationDao.getAllBasePaths()

    fun getAllBasePathsFlow(): Flow<List<String>> =
        syncRelationDao.getAllBasePathsFlow()

    suspend fun save(relation: SyncRelation): Long {
        val entity = SyncRelationEntity(
            id = relation.id,
            name = relation.name,
            syncSourceId = relation.syncSourceId,
            remotePath = relation.remotePath,
            localBasePath = relation.localBasePath,
            lastSyncTime = relation.lastSyncTime,
            lastSyncStatus = relation.lastSyncOutcome?.status?.name,
            lastSyncErrorCode = relation.lastSyncOutcome?.errorCode,
            lastSyncFailedCount = relation.lastSyncOutcome?.failedCount,
            createdAt = relation.createdAt
        )
        return syncRelationDao.upsert(entity)
    }

    /**
     * F02: 接收类型化终态（不接收字符串），时间 + 终态 + 错误码 + 失败数由 DAO
     * 以单条 UPDATE 原子写入。Repository 不生成任何展示文案。
     */
    suspend fun updateLastSync(relationId: Long, outcome: SyncOutcome) {
        syncRelationDao.updateLastSyncSnapshot(
            id = relationId,
            lastSyncTime = System.currentTimeMillis(),
            lastSyncStatus = outcome.status.name,
            lastSyncErrorCode = outcome.errorCode,
            lastSyncFailedCount = outcome.failedCount
        )
    }

    suspend fun delete(id: Long) {
        syncRelationDao.deleteById(id)
    }

    private fun SyncRelationEntity.toDomain() = SyncRelation(
        id = id,
        name = name,
        syncSourceId = syncSourceId,
        remotePath = remotePath,
        localBasePath = localBasePath,
        lastSyncTime = lastSyncTime,
        lastSyncOutcome = SyncOutcome.fromStored(lastSyncStatus, lastSyncErrorCode, lastSyncFailedCount),
        createdAt = createdAt
    )
}
