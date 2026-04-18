package cn.vectory.syncplayer.data.repository

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.SyncProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncSourceRepository @Inject constructor(
    private val syncSourceDao: SyncSourceDao,
    private val masterKey: MasterKey,
    private val encryptedPrefs: EncryptedSharedPreferences
) {
    fun getAllFlow(): Flow<List<SyncSource>> =
        syncSourceDao.getAllFlow().map { list -> list.map { it.toInfo() } }

    suspend fun getAll(): List<SyncSource> =
        syncSourceDao.getAll().map { it.toInfo() }

    suspend fun getAllWithCredentials(): List<SyncSource> =
        syncSourceDao.getAll().map { it.toDomainWithCredentials() }

    suspend fun getById(id: Long): SyncSource? =
        syncSourceDao.getById(id)?.toDomainWithCredentials()

    suspend fun getRelationCount(sourceId: Long): Int =
        syncSourceDao.getRelationCount(sourceId)

    suspend fun save(source: SyncSource): Long {
        val entity = SyncSourceEntity(
            id = source.id,
            name = source.name,
            protocol = source.protocol.name,
            host = source.host,
            port = source.port,
            username = source.username,
            encryptedPassword = ENCRYPTED_PLACEHOLDER
        )
        val savedId = syncSourceDao.upsert(entity)
        val actualId = if (source.id != 0L) source.id else savedId
        encryptedPrefs.edit()
            .putString("pw_$actualId", source.password)
            .apply()
        return savedId
    }

    suspend fun delete(id: Long) {
        syncSourceDao.deleteById(id)
        encryptedPrefs.edit().remove("pw_$id").apply()
    }

    private fun SyncSourceEntity.toInfo() = SyncSource(
        id = id,
        name = name,
        protocol = SyncProtocol.valueOf(protocol),
        host = host,
        port = port,
        username = username,
        password = "",
        createdAt = createdAt
    )

    private fun SyncSourceEntity.toDomainWithCredentials() = SyncSource(
        id = id,
        name = name,
        protocol = SyncProtocol.valueOf(protocol),
        host = host,
        port = port,
        username = username,
        password = encryptedPrefs.getString("pw_$id", null) ?: "",
        createdAt = createdAt
    )

    companion object {
        private const val ENCRYPTED_PLACEHOLDER = "ENCRYPTED"
    }
}
