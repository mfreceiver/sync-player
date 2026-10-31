package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.entity.StorageDatasetEntity
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v5：updateSyncStorageMetadata CAS 语义（0 行 = attempt 失败）与 storage_dataset
 * 单行补种幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class StorageDatasetAndCasCommitTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun insertRow(): Long = runBlocking {
        db.videoItemDao().upsert(
            VideoItemEntity(
                relativePath = "a/x.mp4",
                parentPath = "a",
                fileName = "x.mp4",
                fileSize = 10L
            )
        )
    }

    @Test
    fun casCommitMatchMismatchAndMissingRow() = runBlocking {
        val id = insertRow()
        val iv = ByteArray(8) { it.toByte() }

        // 1) 首次提交：expectedOld = null（PENDING 新建行 generation 为 null）→ 1
        assertEquals(
            1,
            db.videoItemDao().updateSyncStorageMetadata(
                id = id, expectedOldGeneration = null,
                volumeId = "external:card-1", storageFormat = "CTR_V2",
                iv = iv, generation = "gen1", storageState = "READY",
                fileSize = 10L, remoteModifiedTime = null, dateSynced = 1L
            )
        )
        val row = db.videoItemDao().getByIdSync(id)!!
        assertEquals("external:card-1", row.volumeId)
        assertEquals("gen1", row.generation)
        assertEquals("READY", row.storageState)
        org.junit.Assert.assertArrayEquals(iv, row.iv)

        // 2) CAS 失败：仍用 null 竞争（generation 已是 gen1）→ 0，值不变
        assertEquals(
            0,
            db.videoItemDao().updateSyncStorageMetadata(
                id = id, expectedOldGeneration = null,
                volumeId = "internal", storageFormat = "PLAIN",
                iv = null, generation = null, storageState = "READY",
                fileSize = 20L, remoteModifiedTime = null, dateSynced = 2L
            )
        )
        assertEquals("gen1", db.videoItemDao().getByIdSync(id)!!.generation)

        // 3) CAS 成功：带正确旧 generation → 1（重写 attempt 路径）
        assertEquals(
            1,
            db.videoItemDao().updateSyncStorageMetadata(
                id = id, expectedOldGeneration = "gen1",
                volumeId = "external:card-1", storageFormat = "CTR_V2",
                iv = iv, generation = "gen2", storageState = "READY",
                fileSize = 30L, remoteModifiedTime = null, dateSynced = 3L
            )
        )
        assertEquals("gen2", db.videoItemDao().getByIdSync(id)!!.generation)

        // 4) 行不存在 → 0，绝不复活
        assertEquals(
            0,
            db.videoItemDao().updateSyncStorageMetadata(
                id = 999L, expectedOldGeneration = null,
                volumeId = "internal", storageFormat = "PLAIN",
                iv = null, generation = null, storageState = "READY",
                fileSize = 1L, remoteModifiedTime = null, dateSynced = 4L
            )
        )
        assertNull(db.videoItemDao().getByIdSync(999L))
    }

    @Test
    fun datasetEnsureIsIdempotent() = runBlocking {
        assertNull(db.storageDatasetDao().get())
        val dsId = UUID.randomUUID().toString()
        db.storageDatasetDao().ensure(StorageDatasetEntity(id = 1, datasetId = dsId))
        db.storageDatasetDao().ensure(StorageDatasetEntity(id = 1, datasetId = UUID.randomUUID().toString()))
        val row = db.storageDatasetDao().getSync()
        assertNotNull(row)
        assertEquals(dsId, row!!.datasetId)
    }
}
