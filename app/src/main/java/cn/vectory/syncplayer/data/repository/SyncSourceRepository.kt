package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import kotlinx.coroutines.flow.Flow

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncSourceRepository @Inject constructor(
    private val syncSourceDao: SyncSourceDao,
    private val encryptedPassword: String
    private val createdAt: Long = System.currentTimeMillis()
) {
    private val encryptedPassword: String): String {
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
