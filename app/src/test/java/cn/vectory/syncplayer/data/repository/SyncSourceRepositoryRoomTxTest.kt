package cn.vectory.syncplayer.data.repository

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.entity.SyncRelationEntity
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * C3 专属 Room 事务测试：[SyncSourceRepository.delete] 的真实 SQLite 事务回滚证据。
 *
 * 纯 fake（mock DAO + 事件列表）不能替代该证据 —— 本文件用：
 * - 真实 Room in-memory 数据库 + **同一 db + 同一真实 DAO** 承担全部场景；
 * - prefs = 共享 [StatefulPrefsFake]（内存/磁盘两份、脚本化 commit）——prefs 无事务
 *   语义，fake 只保留「commit 先改内存、false/throw 时磁盘滞后」关键子集；
 * - 事务内的 prefs 操作可能落在 Room IO 线程：验收为**不在测试主线程**，
 *   不做「恰在注入线程」的错误断言。
 *
 * 证明目标：
 *  - prefs remove commit 失败 ⇒ 事务内抛错 ⇒ Room 回滚 ⇒ 源行保留（不永久丢源行）；
 *  - sync_relation FK RESTRICT ⇒ 删除被引用源行被数据库拒绝 ⇒ 源行与关系行保留、
 *    凭证恢复（不做已删关联恢复，schema 不改）；
 *  - 成功路径 ⇒ 行删 + 凭证持久移除。
 * 「prefs 成功后事务最终 commit 失败」的场景：**当前 fixture 未提供该注入点**
 * （delete 内部只有 deleteById+remove 两条语句且 deleteById 在前，真实 Room 下
 * 无法在不伪造 Room 语义的前提下制造该时序）——这不是说该失败不可能发生，
 * 只是本文件不测不宣称；若需要覆盖，下一批上报专用接缝，不在本批扩 journal。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class SyncSourceRepositoryRoomTxTest {

    private val fake = StatefulPrefsFake()
    private val encryptedPrefs = mockk<EncryptedSharedPreferences>(relaxed = true)
    private val masterKey = mockk<MasterKey>(relaxed = true)
    private lateinit var db: AppDatabase
    private lateinit var mainThread: Thread

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "c3-io-tx").apply { isDaemon = true }
    }

    @Before
    fun setUp() {
        mainThread = Thread.currentThread()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { encryptedPrefs.getString(any(), any()) } answers {
            fake.getString(firstArg(), secondArg())
        }
        every { encryptedPrefs.edit() } answers { fake.newEditorMock() }
    }

    @After
    fun tearDown() {
        db.close()
        ioExecutor.shutdownNow()
    }

    private fun newRepository() = SyncSourceRepository(
        db.syncSourceDao(), masterKey, encryptedPrefs,
        ioExecutor.asCoroutineDispatcher(), db
    )

    private fun entity(name: String = "nas") = SyncSourceEntity(
        id = 0L, name = name, protocol = "SFTP", host = "h", port = 22,
        username = "u", encryptedPassword = "ENCRYPTED"
    )

    @Test
    fun `delete prefs commit failure rolls the room transaction back preserving the row`() = runTest {
        val dao = db.syncSourceDao()
        val rowId = dao.upsert(entity())
        fake.memory["pw_$rowId"] = "old-pw"
        fake.disk["pw_$rowId"] = "old-pw"
        fake.commitScript.addLast(false) // remove：内存已改、返回 false → 事务内抛错
        fake.commitScript.addLast(true)  // 恢复写

        val thrown = runCatching { newRepository().delete(rowId) }.exceptionOrNull()

        assertTrue("commit=false must fail loudly", thrown is RuntimeException)
        assertNotNull("tx rollback must preserve the source row", dao.getById(rowId))
        assertEquals("credential restored after rollback", "old-pw", fake.disk["pw_$rowId"])
        assertEquals("old-pw", fake.memory["pw_$rowId"])
        fake.getStringThreadAssert(mainThread)
    }

    @Test
    fun `delete with restricted relation preserves source and relation and restores credential`() = runTest {
        val dao = db.syncSourceDao()
        val relationDao = db.syncRelationDao()
        val rowId = dao.upsert(entity())
        relationDao.upsert(
            SyncRelationEntity(
                name = "rel", syncSourceId = rowId,
                remotePath = "/remote", localBasePath = "videos-x"
            )
        )
        fake.memory["pw_$rowId"] = "old-pw"
        fake.disk["pw_$rowId"] = "old-pw"

        val thrown = runCatching { newRepository().delete(rowId) }.exceptionOrNull()

        assertNotNull("FK RESTRICT must reject the delete", thrown)
        assertNotNull("source row preserved", dao.getById(rowId))
        assertEquals(
            "relation row preserved (RESTRICT, no cascade)",
            1, relationDao.getAll().count { it.syncSourceId == rowId }
        )
        assertEquals("old-pw", fake.disk["pw_$rowId"])
        fake.getStringThreadAssert(mainThread)
    }

    @Test
    fun `delete happy path removes row and credential durably`() = runTest {
        val dao = db.syncSourceDao()
        val rowId = dao.upsert(entity())
        fake.memory["pw_$rowId"] = "old-pw"
        fake.disk["pw_$rowId"] = "old-pw"

        newRepository().delete(rowId)

        assertNull("row removed", dao.getById(rowId))
        assertTrue("credential durably removed", !fake.disk.containsKey("pw_$rowId"))
        assertTrue(!fake.memory.containsKey("pw_$rowId"))
    }
}
