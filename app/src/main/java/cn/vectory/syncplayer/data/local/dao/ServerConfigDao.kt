package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {
    @Query("SELECT * FROM server_config WHERE id = 1")
    suspend fun getConfig(): ServerConfigEntity?

    @Query("SELECT * FROM server_config WHERE id = 1")
    fun getConfigFlow(): Flow<ServerConfigEntity?>

    @Upsert
    suspend fun upsertConfig(config: ServerConfigEntity)

    @Query("DELETE FROM server_config WHERE id = 1")
    suspend fun deleteConfig()
}
