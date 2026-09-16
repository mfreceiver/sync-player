package cn.vectory.syncplayer.data.repository

import android.annotation.SuppressLint
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.di.IoDispatcher
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncSourceRepository @Inject constructor(
    private val syncSourceDao: SyncSourceDao,
    private val masterKey: MasterKey,
    private val encryptedPrefs: EncryptedSharedPreferences,
    // AND-081: EncryptedSharedPreferences.commit() is synchronous XML+AES+fsync —
    // it must not run on the caller's (main) dispatcher.
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** Serializes save() operations so concurrent calls for the same source id
     *  cannot race on prefs vs DB ordering. Saves are not a hot path, so a
     *  single mutex is sufficient and avoids per-id map cleanup complexity. */
    private val saveMutex = Mutex()

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

    // commit() (not apply()) is a deliberate contract: the encrypted password must be
    // synchronously durable before the DB row references it, and the boolean result
    // drives rollback (restore previous password / delete the new row).
    @SuppressLint("ApplySharedPref")
    suspend fun save(source: SyncSource): Long = withContext(ioDispatcher) { saveMutex.withLock {
        val entity = SyncSourceEntity(
            id = source.id,
            name = source.name,
            protocol = source.protocol.name,
            host = source.host,
            port = source.port,
            username = source.username,
            encryptedPassword = ENCRYPTED_PLACEHOLDER
        )

        if (source.id != 0L) {
            // Existing source: write encrypted prefs FIRST (synchronously), then DB.
            // This ensures the password is durably stored before the DB row references it.
            // 关键：先快照旧密码。DB 写入失败时必须**恢复旧值**而不是 remove ——
            // 该 key 原本持有一个有效密码，直接删除会把编辑失败升级成凭据丢失。
            val prevPassword = encryptedPrefs.getString("pw_${source.id}", null)
            val prefsOk = encryptedPrefs.edit()
                .putString("pw_${source.id}", source.password)
                .commit()
            if (!prefsOk) {
                throw RuntimeException("Failed to write encrypted password for source ${source.id}")
            }
            try {
                syncSourceDao.upsert(entity)
            } catch (e: Exception) {
                // DB write failed — restore the previous password rather than dropping it.
                encryptedPrefs.edit().apply {
                    if (prevPassword != null) putString("pw_${source.id}", prevPassword)
                    else remove("pw_${source.id}")
                }.commit()
                throw e
            }
        } else {
            // New source (id=0): DB first to get the auto-generated ID, then prefs.
            // We cannot write prefs without knowing the DB-assigned key.
            val savedId = syncSourceDao.upsert(entity)
            val prefsOk = encryptedPrefs.edit()
                .putString("pw_$savedId", source.password)
                .commit()
            if (!prefsOk) {
                // Prefs write failed — roll back the DB row
                syncSourceDao.deleteById(savedId)
                throw RuntimeException("Failed to write encrypted password for new source $savedId")
            }
            savedId
        }
    } }

    /**
     * Shares [saveMutex] with [save] on purpose. Guarding only save/save is not enough — a
     * save/delete interleaving can leave the DB row alive with its password removed (delete
     * wipes prefs between save's prefs-write and DB-upsert), or resurrect a deleted source.
     * Both operations mutate the same (DB row, `pw_<id>` pref) pair, so they must serialize.
     */
    // commit() is deliberate: credential removal must be durable alongside the DB delete;
    // apply()'s async write could outlive process death and leave an orphaned password.
    @SuppressLint("ApplySharedPref")
    suspend fun delete(id: Long) = withContext(ioDispatcher) { saveMutex.withLock {
        syncSourceDao.deleteById(id)
        encryptedPrefs.edit().remove("pw_$id").commit()
        Unit
    } }

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
        password = encryptedPrefs.getString("pw_$id", null) ?: run {
            Log.w(TAG, "Password missing for sync source id=$id, returning empty string")
            ""
        },
        createdAt = createdAt
    )

    companion object {
        private const val TAG = "SyncSourceRepo"
        private const val ENCRYPTED_PLACEHOLDER = "ENCRYPTED"
    }
}
