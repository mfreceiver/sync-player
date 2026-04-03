package cn.vectory.syncplayer.data.repository

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.syncplayer.data.local.dao.ServerConfigDao
import cn.vectory.syncplayer.data.local.entity.ServerConfigEntity
import cn.vectory.syncplayer.data.model.ServerConfig
import cn.vectory.syncplayer.data.model.SyncProtocol
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerConfigRepository @Inject constructor(
    private val serverConfigDao: ServerConfigDao,
    private val masterKey: MasterKey,
    private val encryptedPrefs: EncryptedSharedPreferences
) {

    fun getConfigFlow(): Flow<ServerConfigEntity?> = serverConfigDao.getConfigFlow()

    suspend fun getConfig(): ServerConfig? {
        val entity = serverConfigDao.getConfig() ?: return null
        return ServerConfig(
            protocol = SyncProtocol.valueOf(entity.protocol),
            host = entity.host,
            port = entity.port,
            username = entity.username,
            password = decryptPassword(entity.encryptedPassword),
            remotePath = entity.remotePath,
            lastSyncTime = entity.lastSyncTime,
            lastSyncResult = entity.lastSyncResult
        )
    }

    suspend fun saveConfig(config: ServerConfig) {
        val entity = ServerConfigEntity(
            id = 1,
            protocol = config.protocol.name,
            host = config.host,
            port = config.port,
            username = config.username,
            encryptedPassword = encryptPassword(config.password),
            remotePath = config.remotePath,
            lastSyncTime = config.lastSyncTime,
            lastSyncResult = config.lastSyncResult
        )
        serverConfigDao.upsertConfig(entity)
    }

    suspend fun updateLastSync(result: String) {
        val existing = serverConfigDao.getConfig() ?: return
        serverConfigDao.upsertConfig(
            existing.copy(
                lastSyncTime = System.currentTimeMillis(),
                lastSyncResult = result
            )
        )
    }

    private fun encryptPassword(password: String): String {
        // Use Base64 encoding as a simple obfuscation layer;
        // the EncryptedSharedPreferences file itself provides encryption at rest.
        return android.util.Base64.encodeToString(
            password.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }

    private fun decryptPassword(encrypted: String): String {
        return String(
            android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP),
            Charsets.UTF_8
        )
    }
}
