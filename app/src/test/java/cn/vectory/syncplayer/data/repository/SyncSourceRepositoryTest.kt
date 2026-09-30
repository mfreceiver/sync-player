package cn.vectory.syncplayer.data.repository

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * C3：[SyncSourceRepository] 凭证 I/O 线程归属 + 提交一致性 + 取消语义。
 *
 * 证据边界（如实声明）：
 * - prefs = [StatefulPrefsFake]（共享、精确 editor：独立 pending、commit 先改 memory、
 *   true 才落 disk、脚本化 false/throw）；事件全序 + 最终 memory/disk 双态双重验收；
 * - DB = 真实 Room（Robolectric in-memory）或 mockk DAO（屏障/注错需要）。真实 SQLite
 *   事务回滚证据在 [SyncSourceRepositoryRoomTxTest]；
 * - **每个场景 repository 只构造一次**（标记/锁必须同一实例内观察）；
 * - 屏障 = 真实事件（CompletableDeferred 到达 + release / CountDownLatch 门），
 *   finally 必开，无自旋无 sleep；runCurrent 仅启动子协程。
 * 断言枚举/次数/顺序/最终状态，绝不断言用户文案；输入不含真实秘密。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class SyncSourceRepositoryTest {

    private val fake = StatefulPrefsFake()
    private val encryptedPrefs = mockk<EncryptedSharedPreferences>(relaxed = true)
    private val masterKey = mockk<MasterKey>(relaxed = true)
    private val dao = mockk<SyncSourceDao>(relaxed = true)
    private lateinit var db: AppDatabase
    private lateinit var mainThread: Thread

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "c3-io").apply { isDaemon = true }
    }
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    private fun ioThread(): Thread = ioExecutor.submit(Callable { Thread.currentThread() }).get()

    /** 每个测试恰构造一次；同一实例承担该场景全部 save/read/delete。 */
    private fun newRepository(dao: SyncSourceDao = this.dao): SyncSourceRepository =
        SyncSourceRepository(dao, masterKey, encryptedPrefs, ioDispatcher, db)

    // =========================================================================
    // C7 诊断补强：不改通过标准、不吞原断言。AssertionError → 附加可读诊断，
    // 原失败作为 cause、observed 作为 suppressed（JUnit XML 会完整打印其
    // cause/suppressed 栈）。cause 遍历用 IdentityHashMap 防身份循环。
    // 不做未证实的 recovery 标记识别 / rootCause 比较 / sentinel 统一。
    // =========================================================================

    private fun describeCauseChain(t: Throwable?): String {
        if (t == null) return "null"
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val sb = StringBuilder()
        var cur: Throwable? = t
        while (cur != null && seen.put(cur, true) == null) {
            sb.append(cur::class.java.name).append('@').append(System.identityHashCode(cur))
            cur = cur.cause
            if (cur != null) sb.append(" -> ")
        }
        if (cur != null) sb.append(" ->(identity cycle stopped)")
        sb.append(" | suppressedCount=").append(t.suppressed.size)
        t.suppressed.forEachIndexed { i, s ->
            sb.append(" | suppressed[$i]=")
                .append(s::class.java.name).append('@').append(System.identityHashCode(s))
        }
        return sb.toString()
    }

    private fun assertWithObservedFailure(
        observed: Throwable?,
        key: String,
        injected: List<Throwable?>,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (original: AssertionError) {
            val diag = buildString {
                appendLine("== C7 observed-failure diagnostics ==")
                appendLine("events=${fake.events.toList()}")
                appendLine("commitScriptRemaining=${fake.commitScript.toList()}")
                appendLine("key[$key] memory=${fake.memory[key]} disk=${fake.disk[key]}")
                appendLine("memoryAll=${fake.memory}")
                appendLine("diskAll=${fake.disk}")
                appendLine("observed=${describeCauseChain(observed)}")
                injected.forEachIndexed { i, t ->
                    appendLine("injected[$i]=${t?.let { it::class.java.name + "@" + System.identityHashCode(it) }}")
                }
            }.trimEnd()
            val enriched = AssertionError("$diag\n原始断言: ${original.message}")
            enriched.initCause(original) // 原失败作为 cause：保留原断言语义
            observed?.let { enriched.addSuppressed(it) } // observed 完整栈进 XML
            throw enriched
        }
    }

    private fun existingSource(password: String = "new-pw") = SyncSource(
        id = 5L, name = "nas", protocol = SyncProtocol.SFTP, host = "192.168.1.10",
        port = 22, username = "mar", password = password
    )

    private fun entity(id: Long, name: String = "nas") = SyncSourceEntity(
        id = id, name = name, protocol = "SFTP", host = "h", port = 22,
        username = "u", encryptedPassword = "ENCRYPTED"
    )

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
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 5L }
        coEvery { dao.getById(any()) } answers { null }
    }

    @After
    fun tearDown() {
        db.close()
        ioExecutor.shutdownNow()
    }

    // =========================================================================
    // 1) 注入 IO dispatcher 承载凭证读（次数 + 线程；单一 repository 实例）
    // =========================================================================

    @Test
    fun `credential reads run once per row on the injected io dispatcher`() = runTest {
        val real = db.syncSourceDao()
        real.upsert(entity(0L, "a"))
        real.upsert(entity(0L, "b"))
        val repository = newRepository(real)

        val before = fake.getStringCalls
        assertNotNull(repository.getById(1L))
        val afterOne = fake.getStringCalls
        val list = repository.getAllWithCredentials()

        assertEquals(2, list.size)
        assertEquals("getById reads credentials exactly once", before + 1, afterOne)
        assertEquals("getAllWithCredentials reads once per row", afterOne + 2, fake.getStringCalls)
        val io = ioThread()
        assertTrue(
            "all credential reads on injected io thread, got ${fake.threads.map { it.name }}",
            fake.threads.all { it === io }
        )
    }

    // =========================================================================
    // 2) save 全序 + 最终 memory/disk 双态
    // =========================================================================

    @Test
    fun `save existing source commits prefs then db in exact order`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val repository = newRepository()

        val id = repository.save(existingSource())

        assertEquals(5L, id)
        assertEquals(
            listOf("get:pw_5", "put:pw_5=new-pw", "commit:true", "upsert"),
            fake.events.toList()
        )
        assertEquals("new-pw", fake.disk["pw_5"])
        assertEquals("new-pw", fake.memory["pw_5"])
    }

    @Test
    fun `save new source commits db then prefs and returns generated id`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        val repository = newRepository()

        val id = repository.save(existingSource().copy(id = 0L))

        assertEquals(7L, id)
        assertEquals(
            listOf("upsert", "put:pw_7=new-pw", "commit:true"),
            fake.events.toList()
        )
        assertEquals("new-pw", fake.disk["pw_7"])
    }

    // =========================================================================
    // 3) 唯一恢复入口：commit false / commit throws（内存已变后）/ DB 失败 / DAO CE
    // =========================================================================

    @Test
    fun `existing commit false restores old value with final memory and disk state`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        fake.commitScript.addLast(false) // 主写：内存已改、返回 false
        fake.commitScript.addLast(true)  // 恢复写
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        assertTrue(thrown is RuntimeException)
        assertEquals(
            listOf("get:pw_5", "put:pw_5=new-pw", "commit:false", "put:pw_5=old-pw", "commit:true"),
            fake.events.toList()
        )
        assertEquals("old-pw", fake.memory["pw_5"])
        assertEquals("old-pw", fake.disk["pw_5"])
    }

    @Test
    fun `existing commit throws after memory applied restores and keeps scripted main`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val ioBoom = RuntimeException("prefs io boom")
        fake.commitScript.addLast(ioBoom) // 内存已应用后抛出
        fake.commitScript.addLast(true)
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        // C7：先路径验收（原验收全保留），再异常身份（诊断包覆，不改通过标准）
        assertEquals(
            listOf("get:pw_5", "put:pw_5=new-pw", "commit:throw", "put:pw_5=old-pw", "commit:true"),
            fake.events.toList()
        )
        assertEquals("old-pw", fake.memory["pw_5"])
        assertEquals("old-pw", fake.disk["pw_5"])
        assertWithObservedFailure(thrown, key = "pw_5", injected = listOf(ioBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(ioBoom, primary)
        }
    }

    @Test
    fun `existing db failure restores previous password exactly once and rethrows`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val boom = IllegalStateException("db boom")
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert:boom"); throw boom }
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        // C7：先路径验收，再异常身份
        assertEquals(
            listOf("get:pw_5", "put:pw_5=new-pw", "commit:true", "upsert:boom", "put:pw_5=old-pw", "commit:true"),
            fake.events.toList()
        )
        assertEquals("恢复恰一次", 1, fake.events.count { it == "put:pw_5=old-pw" })
        assertEquals("old-pw", fake.disk["pw_5"])
        assertWithObservedFailure(thrown, key = "pw_5", injected = listOf(boom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(boom, primary)
        }
    }

    @Test
    fun `dao cancellation exception keeps original CE after single restore`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val daoCe = CancellationException("dao internal ce")
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert:ce"); throw daoCe }
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        // C7：先路径验收，再异常身份
        assertEquals(1, fake.events.count { it == "put:pw_5=old-pw" })
        assertEquals("old-pw", fake.disk["pw_5"])
        assertEquals("old-pw", fake.memory["pw_5"])
        assertWithObservedFailure(thrown, key = "pw_5", injected = listOf(daoCe)) {
            assertTrue("外层仍以 CE 传播", thrown is CancellationException)
            val primary = unwrapCoroutineRecovery(thrown!!)
            assertSame("DAO 主动 CE 原样保留（规范化后原对象）", daoCe, primary)
        }
    }

    @Test
    fun `restore failure marks uncertain and only full success clears it`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val repository = newRepository()

        // 第一次失败：主写成功、DB 失败、恢复失败 → uncertain
        val boom = IllegalStateException("db boom")
        coEvery { dao.upsert(any()) } coAnswers { throw boom }
        fake.commitScript.addLast(true)
        fake.commitScript.addLast(false)
        val first = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        // C7：先 fail-closed 路径验收（状态类），再异常身份（诊断包覆）
        val blocked = runCatching { repository.getById(5L) }.exceptionOrNull()
        assertTrue(blocked is SyncSourceCredentialUncertainException)
        assertEquals(5L, (blocked as SyncSourceCredentialUncertainException).sourceId)
        val msg = blocked.message
        assertTrue(msg != null && !msg.contains("new-pw") && !msg.contains("old-pw"))
        assertWithObservedFailure(first, key = "pw_5", injected = listOf(boom)) {
            val primary = first?.let { unwrapCoroutineRecovery(it) }
            assertSame(boom, primary)
            assertEquals("补偿失败程序可观察：suppressed 恰一（在 primary 上）", 1, primary!!.suppressed.size)
        }

        // 第二次重存失败但恢复成功：标记保持（完整成功才清除）
        val boom2 = IllegalStateException("db boom 2")
        coEvery { dao.upsert(any()) } coAnswers { throw boom2 }
        fake.commitScript.addLast(true)
        fake.commitScript.addLast(true)
        runCatching { repository.save(existingSource()) }.exceptionOrNull()
        assertTrue(
            "恢复成功的失败重存仍拒绝读取",
            runCatching { repository.getById(5L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )

        // 第三次完整成功：清除标记，读取恢复
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 5L }
        coEvery { dao.getById(5L) } answers { entity(5L) }
        assertEquals(5L, repository.save(existingSource(password = "fixed-pw")))
        assertEquals("fixed-pw", repository.getById(5L)?.password)
    }

    @Test
    fun `successful repair with caller cancellation still clears the uncertain mark`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val repository = newRepository()

        // 制造 uncertain：主写 false + 恢复 false
        fake.commitScript.addLast(false)
        fake.commitScript.addLast(false)
        runCatching { repository.save(existingSource()) }.exceptionOrNull()
        assertTrue(runCatching { repository.getById(5L) }.exceptionOrNull() is SyncSourceCredentialUncertainException)

        // 成功重存 + 区间内取消：NC 内完成提交并清除标记，区间外 CE
        fake.commitScript.addLast(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers {
            entered.complete(Unit); release.await(); fake.events.add("upsert"); 5L
        }
        val caught = mutableListOf<Throwable>()
        val job = launch {
            try { repository.save(existingSource(password = "fixed-pw")) } catch (t: Throwable) { caught.add(t) }
        }
        try {
            testScheduler.runCurrent()
            entered.await()
            job.cancel(CancellationException("mid-unit"))
            release.complete(Unit)
            job.join()

            assertTrue(caught.single() is CancellationException)
            assertEquals("fixed-pw", fake.disk["pw_5"])
            // 标记已在 NC 内清理（取消不能跳过）：读取不再 fail-closed
            coEvery { dao.getById(5L) } answers { entity(5L) }
            assertEquals("fixed-pw", repository.getById(5L)?.password)
        } finally {
            job.cancel()
            release.complete(Unit)
        }
    }

    // =========================================================================
    // 4) 新源：commit false / throws 都清新键 + 回滚行；回滚失败 suppressed
    // =========================================================================

    @Test
    fun `new source commit false clears new key and rolls back db row`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:${firstArg<Long>()}") }
        fake.commitScript.addLast(false)
        fake.commitScript.addLast(true)
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        assertTrue(thrown is RuntimeException)
        assertEquals(0, thrown!!.suppressed.size)
        assertEquals(
            listOf("upsert", "put:pw_7=new-pw", "commit:false", "remove:pw_7", "commit:true", "deleteById:7"),
            fake.events.toList()
        )
        assertTrue(!fake.disk.containsKey("pw_7"))
        assertTrue(!fake.memory.containsKey("pw_7"))
    }

    @Test
    fun `new source commit throw keeps scripted main and still compensates`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:${firstArg<Long>()}") }
        val ioBoom = RuntimeException("prefs io boom")
        fake.commitScript.addLast(ioBoom)
        fake.commitScript.addLast(true)
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        // C7：先路径验收，再异常身份
        assertEquals(
            listOf("upsert", "put:pw_7=new-pw", "commit:throw", "remove:pw_7", "commit:true", "deleteById:7"),
            fake.events.toList()
        )
        assertTrue(!fake.disk.containsKey("pw_7"))
        assertWithObservedFailure(thrown, key = "pw_7", injected = listOf(ioBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(ioBoom, primary)
        }
    }

    @Test
    fun `new source rollback row failure is suppressed and marks id uncertain`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        val rowBoom = IllegalStateException("row delete boom")
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:boom"); throw rowBoom }
        fake.commitScript.addLast(false)
        fake.commitScript.addLast(true)
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        // C7（0 案例一）：脚本保持 [false, true] 不变；补全路径验收：
        // 主写 false → 清键 true → 行回滚 throws；先验收完整事件/次数/脚本耗尽/双态/拒读。
        assertEquals(
            listOf("upsert", "put:pw_7=new-pw", "commit:false", "remove:pw_7", "commit:true", "deleteById:boom"),
            fake.events.toList()
        )
        assertEquals("回滚尝试恰一次", 1, fake.events.count { it == "deleteById:boom" })
        assertEquals("脚本消费尽", 0, fake.commitScript.size)
        assertTrue(!fake.memory.containsKey("pw_7"))
        assertTrue(!fake.disk.containsKey("pw_7"))
        assertTrue(
            "遗留行 fail-closed",
            runCatching { repository.getById(7L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertWithObservedFailure(thrown, key = "pw_7", injected = listOf(rowBoom)) {
            assertTrue(thrown is RuntimeException)
            val primary = unwrapCoroutineRecovery(thrown!!)
            assertEquals(1, primary.suppressed.size)
            assertSame(rowBoom, primary.suppressed[0])
        }
    }

    // =========================================================================
    // 5) 取消：自取消直接调用 / 等待互斥锁取消 / 快照期间取消（无自旋）
    // =========================================================================

    @Test
    fun `self-cancelled caller enters repo and fails before any store access`() = runTest {
        val repository = newRepository()
        val caught = mutableListOf<Throwable>()

        val job = launch {
            val self = kotlin.coroutines.coroutineContext[Job]!!
            self.cancel(CancellationException("self-cancel"))
            try {
                repository.save(existingSource())
            } catch (t: Throwable) {
                caught.add(t)
            }
        }
        testScheduler.runCurrent()
        job.join()

        assertEquals(1, caught.size)
        assertTrue(caught[0] is CancellationException)
        assertEquals("进入 repo 但未触及快照/存储", 0, fake.getStringCalls)
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun `cancelled waiter blocked on mutex exits with CE without side effects`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val repository = newRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers {
            entered.complete(Unit); release.await(); fake.events.add("upsert"); 5L
        }

        val holder = launch {
            try { repository.save(existingSource()) } catch (_: Throwable) { }
        }
        val waiterCaught = mutableListOf<Throwable>()
        try {
            testScheduler.runCurrent()
            entered.await() // holder 持锁、位于 NC 区间内 DB 屏障

            val waiter = launch {
                try { repository.save(existingSource()) } catch (t: Throwable) { waiterCaught.add(t) }
            }
            testScheduler.runCurrent()
            waiter.cancel(CancellationException("while-waiting"))
            waiter.join()

            assertEquals(1, waiterCaught.size)
            assertTrue(waiterCaught[0] is CancellationException)
            assertEquals(
                "等待者零副作用，holder 独占存储对",
                listOf("get:pw_5", "put:pw_5=new-pw", "commit:true"),
                fake.events.toList()
            )
        } finally {
            release.complete(Unit)
            holder.cancel()
        }
        holder.join()
    }

    @Test
    fun `cancellation during snapshot leaves both stores untouched without spinning`() = runTest {
        val repository = newRepository()
        val reached = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        fake.snapshotReached = reached
        fake.snapshotGate = gate
        val caught = mutableListOf<Throwable>()

        val job = launch {
            try { repository.save(existingSource()) } catch (t: Throwable) { caught.add(t) }
        }
        try {
            reached.await() // 真实事件挂起等待（io 线程已进入 getString、阻塞于门）
            job.cancel(CancellationException("during-snapshot"))
            gate.countDown()
            job.join()

            assertEquals(1, caught.size)
            assertTrue(caught[0] is CancellationException)
            assertEquals(1, fake.getStringCalls)
            assertTrue(
                "无任何修改事件",
                fake.events.none { it.startsWith("put:") || it.startsWith("commit:") }
            )
        } finally {
            gate.countDown()
            job.cancel()
        }
    }

    // =========================================================================
    // 6) 区间内 / 同 dispatcher 取消：完成已提交单元，不回滚
    // =========================================================================

    @Test
    fun `cancellation between stores completes the unit and does not roll back`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val repository = newRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers {
            entered.complete(Unit); release.await(); fake.events.add("upsert"); 5L
        }
        val caught = mutableListOf<Throwable>()
        val job = launch {
            try { repository.save(existingSource()) } catch (t: Throwable) { caught.add(t) }
        }
        try {
            testScheduler.runCurrent()
            entered.await()
            job.cancel(CancellationException("mid-unit"))
            release.complete(Unit)
            job.join()

            assertEquals(1, caught.size)
            assertTrue(caught[0] is CancellationException)
            assertEquals(
                listOf("get:pw_5", "put:pw_5=new-pw", "commit:true", "upsert"),
                fake.events.toList()
            )
            assertEquals("new-pw", fake.disk["pw_5"])
            assertEquals("new-pw", fake.memory["pw_5"])
        } finally {
            job.cancel()
            release.complete(Unit)
        }
    }

    @Test
    fun `caller cancellation on the same io dispatcher still delivers CE after the unit`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val repository = newRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.upsert(any()) } coAnswers {
            entered.complete(Unit); release.await(); fake.events.add("upsert"); 5L
        }
        val caught = mutableListOf<Throwable>()
        val job = launch(ioDispatcher) {
            try { repository.save(existingSource()) } catch (t: Throwable) { caught.add(t) }
        }
        try {
            entered.await()
            job.cancel(CancellationException("same-dispatcher"))
            release.complete(Unit)
            job.join()

            assertEquals(1, caught.size)
            assertTrue(caught[0] is CancellationException)
            assertEquals("new-pw", fake.disk["pw_5"])
        } finally {
            job.cancel()
            release.complete(Unit)
        }
    }

    // =========================================================================
    // 7) 读取共享互斥锁：读者自己的结果（不吞异常再另读冒证据）
    // =========================================================================

    @Test
    fun `reader blocked by mutex observes the committed pair in its own result`() = runTest {
        val rows = LinkedHashMap<Long, SyncSourceEntity>()
        rows[5L] = entity(5L, name = "old-name")
        val stateful = mockk<SyncSourceDao>(relaxed = true)
        coEvery { stateful.upsert(any()) } coAnswers {
            val e = firstArg<SyncSourceEntity>()
            enteredTx?.complete(Unit)
            releaseTx?.await()
            rows[e.id] = e.copy(name = "new-name")
            fake.events.add("upsert"); e.id
        }
        coEvery { stateful.getAll() } answers { rows.values.toList() }
        val repository = newRepository(stateful)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        enteredTx = entered; releaseTx = release
        val readerResult = CompletableDeferred<List<SyncSource>>()
        val reader = launch(start = CoroutineStart.LAZY) {
            try {
                readerResult.complete(repository.getAllWithCredentials())
            } catch (t: Throwable) {
                readerResult.completeExceptionally(t)
            }
        }
        val writer = launch {
            try { repository.save(existingSource()) } catch (_: Throwable) { }
        }
        try {
            testScheduler.runCurrent()
            entered.await() // writer 持锁、位于 DB 屏障

            reader.start() // 此时才发起读取
            testScheduler.runCurrent() // reader 运行 → 阻塞于互斥锁
            assertEquals(
                "reader 未进 DAO/prefs",
                listOf("get:pw_5", "put:pw_5=new-pw", "commit:true"),
                fake.events.toList()
            )

            release.complete(Unit)
            writer.join()
            val snapshot = readerResult.await() // 读者自己的结果
            assertEquals("new-name", snapshot.single { it.id == 5L }.name)
            assertEquals("new-pw", snapshot.single { it.id == 5L }.password)
            val upsertIdx = fake.events.indexOfFirst { it == "upsert" }
            val readerGetIdx = fake.events.indexOfLast { it == "get:pw_5" }
            assertTrue("reader 的读取在 writer 提交之后", readerGetIdx > upsertIdx)
        } finally {
            release.complete(Unit)
            reader.cancel(); writer.cancel()
            enteredTx = null; releaseTx = null
        }
    }

    private var enteredTx: CompletableDeferred<Unit>? = null
    private var releaseTx: CompletableDeferred<Unit>? = null

    // =========================================================================
    // 8) delete：区间内取消完成双存储（真实 Room 事务机 + mock DAO 屏障）
    // =========================================================================

    @Test
    fun `cancellation during delete completes both stores then propagates`() = runTest {
        fake.memory["pw_9"] = "old-pw"
        fake.disk["pw_9"] = "old-pw"
        val dao = mockk<SyncSourceDao>(relaxed = true)
        coEvery { dao.deleteById(any()) } coAnswers {
            enteredDel?.complete(Unit); releaseDel?.await(); fake.events.add("deleteById:9")
        }
        val repository = newRepository(dao)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        enteredDel = entered; releaseDel = release
        val caught = mutableListOf<Throwable>()
        val job = launch {
            try { repository.delete(9L) } catch (t: Throwable) { caught.add(t) }
        }
        try {
            testScheduler.runCurrent()
            entered.await()
            job.cancel(CancellationException("mid-delete"))
            release.complete(Unit)
            job.join()

            assertEquals(1, caught.size)
            assertTrue(caught[0] is CancellationException)
            assertEquals(
                listOf("get:pw_9", "deleteById:9", "remove:pw_9", "commit:true"),
                fake.events.toList()
            )
            assertTrue(!fake.disk.containsKey("pw_9"))
            assertTrue(!fake.memory.containsKey("pw_9"))
            // 凭证操作不得在测试主线程（事务机线程与注入线程都合法）
            fake.getStringThreadAssert(mainThread)
        } finally {
            job.cancel()
            release.complete(Unit)
            enteredDel = null; releaseDel = null
        }
    }

    // =========================================================================
    // 9) C5 最低矩阵：补偿各尝试恰一次、主身份不变、自抑制、fail-closed、日志故障
    //    脚本消费顺序均在注释中标明（队首 → 队尾依次出队）。
    // =========================================================================

    @Test
    fun `new source main throw with clear-key false still deletes db row exactly once`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:${firstArg<Long>()}") }
        val ioBoom = RuntimeException("prefs io boom")
        fake.commitScript.addLast(ioBoom) // ① 主写：内存已变后抛出
        fake.commitScript.addLast(false)  // ② 清键：内存已删、返回 false
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        // C7：先路径验收，再异常身份（脚本消费顺序 ①主写 throws ②清键 false）
        assertEquals(
            listOf("upsert", "put:pw_7=new-pw", "commit:throw", "remove:pw_7", "commit:false", "deleteById:7"),
            fake.events.toList()
        )
        assertEquals("DB 回滚行恰一次", 1, fake.events.count { it == "deleteById:7" })
        assertTrue(!fake.memory.containsKey("pw_7"))
        assertTrue(!fake.disk.containsKey("pw_7"))
        assertWithObservedFailure(thrown, key = "pw_7", injected = listOf(ioBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(ioBoom, primary)
            assertEquals("清键失败恰一个 suppressed marker（在 primary 上）", 1, primary!!.suppressed.size)
        }
    }

    @Test
    fun `new source main throw with clear-key throw still deletes db row exactly once`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:${firstArg<Long>()}") }
        val ioBoom = RuntimeException("prefs io boom")
        val clearBoom = IllegalStateException("clear boom")
        fake.commitScript.addLast(ioBoom)   // ① 主写 throws
        fake.commitScript.addLast(clearBoom) // ② 清键 throws
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        // C7：先路径验收，再异常身份（①主写 throws ②清键 throws）
        assertEquals(
            listOf("upsert", "put:pw_7=new-pw", "commit:throw", "remove:pw_7", "commit:throw", "deleteById:7"),
            fake.events.toList()
        )
        assertEquals(1, fake.events.count { it == "deleteById:7" })
        assertTrue(!fake.memory.containsKey("pw_7"))
        assertTrue(!fake.disk.containsKey("pw_7"))
        assertWithObservedFailure(thrown, key = "pw_7", injected = listOf(ioBoom, clearBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(ioBoom, primary)
            assertSame(clearBoom, primary!!.suppressed[0])
            assertEquals(1, primary.suppressed.size)
        }
    }

    @Test
    fun `new source both compensations fail keeps main identity and both reads fail closed`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        val rowBoom = IllegalStateException("row delete boom")
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:boom"); throw rowBoom }
        coEvery { dao.getAll() } answers { listOf(entity(7L)) }
        val ioBoom = RuntimeException("prefs io boom")
        val clearBoom = IllegalStateException("clear boom")
        fake.commitScript.addLast(ioBoom)    // ① 主写 throws
        fake.commitScript.addLast(clearBoom) // ② 清键 throws
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        // C7：先路径验收（回滚次数 + 遗留行双读取 fail-closed），再异常身份
        assertEquals("行回滚恰一次不重试", 1, fake.events.count { it == "deleteById:boom" })
        assertTrue(
            runCatching { repository.getById(7L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertTrue(
            runCatching { repository.getAllWithCredentials() }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertWithObservedFailure(thrown, key = "pw_7", injected = listOf(ioBoom, clearBoom, rowBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(ioBoom, primary)
            assertEquals("suppressed 顺序 = [清键失败, 行回滚失败]（在 primary 上）", listOf<Throwable>(clearBoom, rowBoom), primary!!.suppressed.toList())
        }
    }

    @Test
    fun `same throwable for main and clear-key never self-suppresses and later compensation runs`() = runTest {
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert"); 7L }
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:${firstArg<Long>()}") }
        val ioBoom = RuntimeException("prefs io boom")
        fake.commitScript.addLast(ioBoom) // ① 主写 throws
        fake.commitScript.addLast(ioBoom) // ② 清键抛**同一实例**：不得自抑制
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource().copy(id = 0L)) }.exceptionOrNull()

        // C7：先路径验收（后续补偿仍执行），再异常身份（同实例自抑制检查）
        assertEquals("后续补偿仍执行：行回滚恰一次", 1, fake.events.count { it == "deleteById:7" })
        assertTrue(!fake.disk.containsKey("pw_7"))
        assertWithObservedFailure(thrown, key = "pw_7", injected = listOf(ioBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(ioBoom, primary)
            assertEquals("同对象不得进入 suppressed（自抑制保护，在 primary 上）", 0, primary!!.suppressed.size)
        }
    }

    @Test
    fun `existing restore throw keeps main identity and marks uncertain with real final state`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val dbBoom = IllegalStateException("db boom")
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert:boom"); throw dbBoom }
        val restoreBoom = IllegalStateException("restore boom")
        fake.commitScript.addLast(true)        // ① 主写 true：disk 落 new-pw
        fake.commitScript.addLast(restoreBoom) // ② 恢复写：memory 已改回 old-pw 后抛出
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        // C7：先路径验收（真实最终不匹配态 + uncertain），再异常身份
        assertEquals(
            listOf("get:pw_5", "put:pw_5=new-pw", "commit:true", "upsert:boom", "put:pw_5=old-pw", "commit:throw"),
            fake.events.toList()
        )
        assertEquals("old-pw", fake.memory["pw_5"])
        assertEquals("new-pw", fake.disk["pw_5"])
        assertTrue(
            runCatching { repository.getById(5L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertWithObservedFailure(thrown, key = "pw_5", injected = listOf(dbBoom, restoreBoom)) {
            val primary = thrown?.let { unwrapCoroutineRecovery(it) }
            assertSame(dbBoom, primary)
            assertEquals(listOf<Throwable>(restoreBoom), primary!!.suppressed.toList())
        }
    }

    @Test
    fun `delete restore failure keeps both reads fail closed`() = runTest {
        fake.memory["pw_9"] = "old-pw"
        fake.disk["pw_9"] = "old-pw"
        val dao = mockk<SyncSourceDao>(relaxed = true)
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:9") }
        coEvery { dao.getAll() } answers { listOf(entity(9L)) }
        fake.commitScript.addLast(false) // ① remove：内存已删、false → 事务内抛错回滚
        fake.commitScript.addLast(false) // ② 恢复写：memory 改回 old-pw、false
        val repository = newRepository(dao)

        val thrown = runCatching { repository.delete(9L) }.exceptionOrNull()

        // C7（0 案例二）：先验证既有完整事件、双态旧值、双读拒绝，再检查 suppressed
        assertEquals(
            listOf("get:pw_9", "deleteById:9", "remove:pw_9", "commit:false", "put:pw_9=old-pw", "commit:false"),
            fake.events.toList()
        )
        assertEquals("脚本消费尽", 0, fake.commitScript.size)
        assertEquals("old-pw", fake.memory["pw_9"])
        assertEquals("old-pw", fake.disk["pw_9"])
        assertTrue(
            runCatching { repository.getById(9L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertTrue(
            runCatching { repository.getAllWithCredentials() }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertWithObservedFailure(thrown, key = "pw_9", injected = emptyList()) {
            assertTrue(thrown is RuntimeException)
            val primary = unwrapCoroutineRecovery(thrown!!)
            assertEquals("primary 上恰一个恢复失败 marker（生产修复验收）", 1, primary.suppressed.size)
        }
    }

    @Test
    fun `log failure during markUncertain does not mask main nor skip marking`() = runTest {
        io.mockk.mockkStatic(android.util.Log::class)
        try {
            val logBoom = RuntimeException("log boom")
            io.mockk.every { android.util.Log.w(any<String>(), any<String>()) } throws logBoom
            fake.memory["pw_5"] = "old-pw"
            fake.disk["pw_5"] = "old-pw"
            val dbBoom = IllegalStateException("db boom")
            coEvery { dao.upsert(any()) } coAnswers { throw dbBoom }
            fake.commitScript.addLast(true)  // ① 主写 true
            fake.commitScript.addLast(false) // ② 恢复写 false → markUncertain（Log.w 抛出）
            val repository = newRepository()

            val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

            // C7：先验证标记路径 + Log.w 精确命中，再异常身份（诊断包覆）
            assertTrue(
                "标记先于日志完成：fail-closed 仍生效",
                runCatching { repository.getById(5L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
            )
            io.mockk.verify(exactly = 1) { android.util.Log.w(any<String>(), any<String>()) }
            assertWithObservedFailure(thrown, key = "pw_5", injected = listOf(dbBoom)) {
                val primary = thrown?.let { unwrapCoroutineRecovery(it) }
                assertSame("日志故障不得遮盖主异常", dbBoom, primary)
                assertEquals("日志故障不产生额外 suppressed（在 primary 上）", 1, primary!!.suppressed.size)
            }
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
        }
    }

    // =========================================================================
    // 10) C8：协程恢复层识别回归（先于生产修改编写；静态未运行）
    //     恢复帧三元组来自 rev-2 C7 取证：_COROUTINE._BOUNDARY / _ / CoroutineDebugging.kt（不绑行号）。
    //     helper 契约：仅剥「同运行时类型 + 精确恢复帧 + suppressed 为空」的框架层；
    //     不比较 message、不无条件 rootCause、不吞业务 wrapper。
    // =========================================================================

    /** 构造与 cause 同运行时类型、带精确恢复帧、无 suppressed 的合成恢复层。 */
    private fun recoveryLayer(cause: Throwable): Throwable {
        val layer = cause.javaClass.getDeclaredConstructor(String::class.java).newInstance("recovery-copy")
        layer.initCause(cause)
        layer.stackTrace = arrayOf(
            StackTraceElement("_COROUTINE._BOUNDARY", "_", "CoroutineDebugging.kt", -1),
            *cause.stackTrace
        )
        return layer
    }

    @Test
    fun `unwrap strips same-type recovery layer and returns original object`() {
        val original = RuntimeException("original")
        val layer = recoveryLayer(original)

        assertSame(original, unwrapCoroutineRecovery(layer))
    }

    @Test
    fun `ordinary same-type business wrapper is preserved`() {
        val original = IllegalStateException("original")
        val wrapper = IllegalStateException("business-wrapper", original)

        assertSame(wrapper, unwrapCoroutineRecovery(wrapper))
        assertSame(original, wrapper.cause)
    }

    @Test
    fun `recovery layer over business wrapper stops at the wrapper`() {
        val original = RuntimeException("original")
        val business = RuntimeException("business-wrapper", original) // 无恢复帧
        val layer = recoveryLayer(business)

        assertSame(business, unwrapCoroutineRecovery(layer))
    }

    @Test
    fun `different runtime type cause is preserved at current layer`() {
        val layer = RuntimeException("wrapper")
        layer.initCause(java.io.IOException("io"))
        layer.stackTrace = arrayOf(StackTraceElement("_COROUTINE._BOUNDARY", "_", "CoroutineDebugging.kt", -1))

        assertSame(layer, unwrapCoroutineRecovery(layer))
    }

    @Test
    fun `same-type layer without cause returns itself`() {
        val t = RuntimeException("no-cause")

        assertSame(t, unwrapCoroutineRecovery(t))
    }

    @Test
    fun `two-node cause cycle returns the original input`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        val frame = arrayOf(StackTraceElement("_COROUTINE._BOUNDARY", "_", "CoroutineDebugging.kt", -1))
        a.stackTrace = frame
        b.stackTrace = frame

        assertSame(a, unwrapCoroutineRecovery(a))
    }

    @Test
    fun `layer with existing suppressed is not stripped`() {
        val original = RuntimeException("original")
        val innerDiag = RuntimeException("inner-diag")
        val layer = recoveryLayer(original)
        layer.addSuppressed(innerDiag)

        assertSame(layer, unwrapCoroutineRecovery(layer))
        assertEquals(1, layer.suppressed.size)
        assertSame(innerDiag, layer.suppressed[0])
    }

    @Test
    fun `dao ordinary CE with restore failure keeps CE type original object marker and uncertain`() = runTest {
        fake.memory["pw_5"] = "old-pw"
        fake.disk["pw_5"] = "old-pw"
        val daoCe = CancellationException("dao internal ce")
        coEvery { dao.upsert(any()) } coAnswers { fake.events.add("upsert:ce"); throw daoCe }
        fake.commitScript.addLast(true)  // ① 主写 true：disk 落 new-pw
        fake.commitScript.addLast(false) // ② 恢复写 false → marker + uncertain
        val repository = newRepository()

        val thrown = runCatching { repository.save(existingSource()) }.exceptionOrNull()

        // 先路径验收（事件/双态/拒读），再异常身份
        assertEquals(
            listOf("get:pw_5", "put:pw_5=new-pw", "commit:true", "upsert:ce", "put:pw_5=old-pw", "commit:false"),
            fake.events.toList()
        )
        assertEquals("old-pw", fake.memory["pw_5"])
        assertEquals("new-pw", fake.disk["pw_5"])
        assertTrue(
            runCatching { repository.getById(5L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertWithObservedFailure(thrown, key = "pw_5", injected = listOf(daoCe)) {
            assertTrue("外层仍以 CE 传播", thrown is CancellationException)
            val primary = unwrapCoroutineRecovery(thrown!!)
            assertSame("规范化后是注入的原 CE 对象", daoCe, primary)
            assertEquals("恢复失败 marker 挂在原主异常上", 1, primary.suppressed.size)
        }
    }

    @Test
    fun `delete restore throw keeps compensation marker on primary with full path evidence`() = runTest {
        fake.memory["pw_9"] = "old-pw"
        fake.disk["pw_9"] = "old-pw"
        val dao = mockk<SyncSourceDao>(relaxed = true)
        coEvery { dao.deleteById(any()) } coAnswers { fake.events.add("deleteById:9") }
        coEvery { dao.getAll() } answers { listOf(entity(9L)) }
        val restoreBoom = IllegalStateException("restore boom")
        fake.commitScript.addLast(false)      // ① remove false：事务内抛生产主异常
        fake.commitScript.addLast(restoreBoom) // ② 恢复写 throws
        val repository = newRepository(dao)

        val thrown = runCatching { repository.delete(9L) }.exceptionOrNull()

        // 先路径验收：完整事件、脚本耗尽、双态、双读拒绝
        assertEquals(
            listOf("get:pw_9", "deleteById:9", "remove:pw_9", "commit:false", "put:pw_9=old-pw", "commit:throw"),
            fake.events.toList()
        )
        assertEquals("脚本消费尽", 0, fake.commitScript.size)
        assertEquals("old-pw", fake.memory["pw_9"])
        assertEquals("old-pw", fake.disk["pw_9"])
        assertTrue(
            runCatching { repository.getById(9L) }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertTrue(
            runCatching { repository.getAllWithCredentials() }.exceptionOrNull() is SyncSourceCredentialUncertainException
        )
        assertWithObservedFailure(thrown, key = "pw_9", injected = listOf(restoreBoom)) {
            assertTrue(thrown is RuntimeException)
            val primary = unwrapCoroutineRecovery(thrown!!)
            assertEquals("补偿诊断保留在 primary 上（生产修复验收）", 1, primary.suppressed.size)
            assertSame(restoreBoom, primary.suppressed.single())
        }
    }

    private var enteredDel: CompletableDeferred<Unit>? = null
    private var releaseDel: CompletableDeferred<Unit>? = null
}
