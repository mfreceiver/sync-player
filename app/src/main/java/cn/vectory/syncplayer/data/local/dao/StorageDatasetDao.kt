package cn.vectory.syncplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.vectory.syncplayer.data.local.entity.StorageDatasetEntity

@Dao
interface StorageDatasetDao {

    @Query("SELECT * FROM storage_dataset WHERE id = 1")
    suspend fun get(): StorageDatasetEntity?

    /** 同步版（Provider/同步开始的 binder 线程快照）。 */
    @Query("SELECT * FROM storage_dataset WHERE id = 1")
    fun getSync(): StorageDatasetEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(entity: StorageDatasetEntity)

    @Query("DELETE FROM storage_dataset")
    suspend fun clear()
}
