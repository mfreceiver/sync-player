package cn.vectory.syncplayer.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P3/P4 导航协调层测试：
 * - 恢复回填（全量/降级）、🟠3 恢复绑定（类型显式、route 真源）、
 *   🟠4 每进程一次性恢复、内存快路径；
 * - 🔴2 点击顺序协议：登记序 = 事务（prepare→setIds→onReady）执行序，
 *   缓存复用快路径不插队，连续点击最终页面 = 最后一次点击。
 */
class QueueNavigationCoordinatorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val io: CoroutineDispatcher = Dispatchers.IO

    private lateinit var dir: File
    private lateinit var videoQueue: VideoQueue
    private lateinit var imageQueue: ImageQueue

    /// 合法格式的 QSNAP2 token 字面量（mockk store 场景仅验证协调层门控行为）。
    private val anyValidToken = "QSNAP2|video|" + "ab".repeat(16) + "|1|full"

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        dir = tmpFolder.newFolder("queue_snapshots")
        videoQueue = VideoQueue()
        imageQueue = ImageQueue()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun store(): QueueSnapshotStore =
        QueueSnapshotStore(dir, io, QueueSnapshotStore.DEFAULT_MAX_FILE_BYTES, QueueSnapshotStore.DEFAULT_MAX_IDS)

    /** 可控 IO：写入被 gate 在调度器上，用于验证登记序不被 IO 完成序打乱。 */
    private fun gatedStore(scheduler: TestCoroutineScheduler): QueueSnapshotStore =
        QueueSnapshotStore(dir, StandardTestDispatcher(scheduler), QueueSnapshotStore.DEFAULT_MAX_FILE_BYTES, QueueSnapshotStore.DEFAULT_MAX_IDS)

    private fun videoFile(): File = File(dir, QueueMediaType.VIDEO.snapshotFileName)

    private fun imageFile(): File = File(dir, QueueMediaType.IMAGE.snapshotFileName)

    /** navigateToPlayer/Image 的 ready 回调在真实 IO 跳转后主线程延续触发：有界等待。 */
    private fun awaitReady(ready: AtomicBoolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!ready.get()) {
            assertTrue("navigation prepare did not complete in time", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    // ---- 导航前置事务 ------------------------------------------------------

    @Test
    fun `navigateToPlayer seeds queue and emits full token after snapshot write`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)

        coordinator.navigateToPlayer(currentId = 2L, ids = listOf(1L, 2L, 3L)) { token ->
            tokenRef.set(token)
            ready.set(true)
        }
        awaitReady(ready)

        val token = tokenRef.get()!!
        assertEquals(QueueToken.Mode.FULL, QueueToken.decode(token)!!.mode)
        assertEquals(2L, QueueToken.decode(token)!!.currentId)
        assertTrue(videoFile().isFile)
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `navigateToImageViewer seeds image queue and writes image snapshot`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)

        coordinator.navigateToImageViewer(currentId = 5L, ids = listOf(4L, 5L, 6L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        assertTrue(imageFile().isFile)
        assertFalse(videoFile().exists())
        assertEquals(QueueMediaType.IMAGE, QueueToken.decode(tokenRef.get())!!.type)
        assertEquals(listOf(4L, 5L, 6L), imageQueue.getIds())
    }

    @Test
    fun `navigateToPlayer with empty ids guards to single item queue`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        coordinator.navigateToPlayer(currentId = 7L, ids = emptyList()) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        // 协调层守卫把空列表规范为 [currentId]：完整单项队列 + FULL 单元素快照。
        assertEquals(listOf(7L), videoQueue.getIds())
        val decoded = QueueToken.decode(tokenRef.get())!!
        assertEquals(QueueToken.Mode.FULL, decoded.mode)
        assertEquals(7L, decoded.currentId)
    }

    // ---- 🔴2 点击顺序协议 --------------------------------------------------

    @Test
    fun `cache hit never overtakes an in-flight earlier click`() {
        val scheduler = TestCoroutineScheduler()
        val coordinator = QueueNavigationCoordinator(gatedStore(scheduler), videoQueue, imageQueue)
        val order = Collections.synchronizedList(mutableListOf<String>())

        // A：新列表 → 走真实写路径，被 gate 挂起；
        coordinator.navigateToPlayer(1L, listOf(1L, 2L, 3L)) { order.add("A:${QueueToken.decode(it)!!.currentId}") }
        // B：同列表 → 命中缓存复用（无 IO），但必须排队，不得先于 A 导航。
        coordinator.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { order.add("B:${QueueToken.decode(it)!!.currentId}") }

        assertEquals("gated A must block B (FIFO), order=$order", emptyList<String>(), order)

        scheduler.advanceUntilIdle()

        assertEquals(listOf("A:1", "B:2"), order)
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `reverse io completion never changes navigation order or token identity`() {
        val scheduler = TestCoroutineScheduler()
        val coordinator = QueueNavigationCoordinator(gatedStore(scheduler), videoQueue, imageQueue)
        val order = Collections.synchronizedList(mutableListOf<String>())

        coordinator.navigateToPlayer(5L, listOf(5L, 6L, 7L)) { order.add("C:${QueueToken.decode(it)!!.currentId}") }
        coordinator.navigateToPlayer(8L, listOf(8L, 9L)) { order.add("D:${QueueToken.decode(it)!!.currentId}") }

        scheduler.advanceUntilIdle()

        // 两个 IO 都被 gate：放行顺序由调度器决定，导航序必须仍等于登记序，
        // 且每个 token 的身份与其点击一一对应。
        assertEquals(listOf("C:5", "D:8"), order)
        assertEquals(listOf(8L, 9L), videoQueue.getIds())
    }

    @Test
    fun `consecutive clicks always land on the last clicked queue`() {
        val scheduler = TestCoroutineScheduler()
        val coordinator = QueueNavigationCoordinator(gatedStore(scheduler), videoQueue, imageQueue)
        val order = Collections.synchronizedList(mutableListOf<Long>())

        coordinator.navigateToPlayer(1L, listOf(1L, 11L)) { order.add(QueueToken.decode(it)!!.currentId) }
        coordinator.navigateToPlayer(2L, listOf(2L, 22L)) { order.add(QueueToken.decode(it)!!.currentId) }
        coordinator.navigateToPlayer(3L, listOf(3L, 33L)) { order.add(QueueToken.decode(it)!!.currentId) }

        scheduler.advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), order)
        assertEquals("final page must match the last click", listOf(3L, 33L), videoQueue.getIds())
    }

    // ---- 🟠3 恢复绑定（route 参数为真源） ----------------------------------

    @Test
    fun `restore seeds full queue from valid token on cold store`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        // 全新 store 实例模拟进程重启；协调层亦全新（内存队列空）。
        // routeCurrentId 取列表内成员（1 ∈ [1,2,3]）→ 接受全量；
        // route 项不在列表的降级场景见专项用例。
        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 1L)
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `image token under video handle degrades to single route item`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToImageViewer(5L, listOf(4L, 5L, 6L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        // 合法 IMAGE 快照与 token 存在，但注入 VIDEO handle：类型显式绑定 → 拒绝 → SINGLE(route)。
        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())),
            routeCurrentId = 42L,
        )
        assertEquals(listOf(42L), videoQueue.getIds())
    }

    @Test
    fun `token current id differing from route item still restores full list when route item present`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        // token.currentId=2 与 route=1 不一致（进程内切媒遗留），但 route 项在列表内 → 接受全量。
        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 1L)
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `route item absent from restored list degrades to single route item`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        // 恢复列表有效但不包含 route 当前项 → 降级为仅 route 当前项。
        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 99L)
        assertEquals(listOf(99L), videoQueue.getIds())
    }

    // ---- 恢复降级 / 快路径 / 一次性 ----------------------------------------

    @Test
    fun `restore degrades to single fallback item when token is missing`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        coordinator.restoreVideoQueue(SavedStateHandle(), routeCurrentId = 42L)
        assertEquals(listOf(42L), videoQueue.getIds())
    }

    @Test
    fun `restore degrades to single fallback item when snapshot is corrupted`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        val bytes = videoFile().readBytes()
        videoFile().writeBytes(bytes.copyOf(bytes.size / 2))

        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 2L)
        assertEquals(listOf(2L), videoQueue.getIds())
    }

    @Test
    fun `restore uses memory fast path even when snapshot file is gone`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        coordinator.navigateToPlayer(3L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        // 热进程内（同 token 未失效）文件丢失也不应回退整库或单项：内存快路径兜住。
        assertTrue(videoFile().delete())
        coordinator.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())),
            routeCurrentId = 3L,
        )
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `restore executes once per token per process and re-composition is a no-op`() {
        val store = mockk<QueueSnapshotStore>()
        every { store.readForRestoreBlocking(any(), any()) } returns listOf(1L, 2L, 3L)
        val coordinator = QueueNavigationCoordinator(store, videoQueue, imageQueue)

        val handle = SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to anyValidToken))
        coordinator.restoreVideoQueue(handle, routeCurrentId = 1L)
        coordinator.restoreVideoQueue(handle, routeCurrentId = 1L)

        // 🟠4：同一 token 每进程只读一次磁盘，重复组合直接跳过。
        verify(exactly = 1) { store.readForRestoreBlocking(any(), any()) }
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `restoreImageQueue degrades to fallback on missing token`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        coordinator.restoreImageQueue(SavedStateHandle(), routeCurrentId = 8L)
        assertEquals(listOf(8L), imageQueue.getIds())
    }

    @Test
    fun `null fallback never seeds queue`() {
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        coordinator.restoreVideoQueue(SavedStateHandle(), routeCurrentId = null)
        coordinator.restoreImageQueue(SavedStateHandle(), routeCurrentId = null)
        assertTrue(videoQueue.getIds().isEmpty())
        assertTrue(imageQueue.getIds().isEmpty())
        assertNull(QueueToken.decode(null))
    }

    // ---- P5 🔴1 回填门控按消费方身份 ---------------------------------------

    @Test
    fun `two entries without tokens each backfill their own route item`() {
        // 缺失 token 必须按 entry（handle 身份）区分，不得全进程合并去重。
        val coordinator = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        coordinator.restoreVideoQueue(SavedStateHandle(), routeCurrentId = 7L)
        assertEquals(listOf(7L), videoQueue.getIds())
        coordinator.restoreVideoQueue(SavedStateHandle(), routeCurrentId = 9L)
        assertEquals(listOf(9L), videoQueue.getIds())
    }

    @Test
    fun `two consumers sharing one valid token each backfill successfully`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 1L
        )
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
        // 第二个 entry（不同 SavedStateHandle）共用同一 token：先消费/清空队列，
        // 使第二次回填真实可观察（不得被去重吞掉）。
        assertTrue(videoQueue.consumeIds(1L).isNotEmpty())
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 2L
        )
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `coordinator rebuild (fresh instance) for the same entry re-backfills with its route item`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        val handle = SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get()))
        val first = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        first.restoreVideoQueue(handle, routeCurrentId = 1L)
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())

        // 构造全新 Coordinator 实例（消费方/进程重建语义）：同一 entry 重新
        // 回填且队列含自己 route 当前项。
        val rebuilt = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        rebuilt.restoreVideoQueue(handle, routeCurrentId = 3L)
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
    }

    @Test
    fun `failed restore does not register consumer and is retried on next call`() {
        val store = mockk<QueueSnapshotStore>()
        every { store.readForRestoreBlocking(any(), any()) } returns null
        val coordinator = QueueNavigationCoordinator(store, videoQueue, imageQueue)
        val handle = SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to anyValidToken))
        coordinator.restoreVideoQueue(handle, routeCurrentId = 5L)
        coordinator.restoreVideoQueue(handle, routeCurrentId = 5L)
        // 失败不登记"已恢复"：第二次调用允许重试（重试仍失败则再降级）。
        verify(exactly = 2) { store.readForRestoreBlocking(any(), any()) }
        assertEquals(listOf(5L), videoQueue.getIds())
    }

    // ---- P5 🟠3 Channel 生命周期 -------------------------------------------

    @Test
    fun `scope teardown during in-flight prepare drops queued transactions without navigation leak`() {
        val scheduler = TestCoroutineScheduler()
        val coordinator = QueueNavigationCoordinator(gatedStore(scheduler), videoQueue, imageQueue)
        val leaked = Collections.synchronizedList(mutableListOf<String>())
        coordinator.navigateToPlayer(1L, listOf(1L, 11L)) { leaked.add("A") }
        coordinator.navigateToPlayer(2L, listOf(2L, 22L)) { leaked.add("B") }
        coordinator.viewModelScope.cancel()
        scheduler.advanceUntilIdle()
        assertTrue("no navigation may leak after teardown", leaked.isEmpty())
        assertTrue("cancelled transactions must not seed the queue", videoQueue.getIds().isEmpty())
        // 🔴2 可观察证据：通道已终止且缓冲积压被丢弃（非仅 onReady 未执行）。
        assertTrue(coordinator.navQueueDrainedForTest)
    }

    @Test
    fun `ordinary exception in one transaction does not kill the consumer`() {
        val scheduler = TestCoroutineScheduler()
        val coordinator = QueueNavigationCoordinator(gatedStore(scheduler), videoQueue, imageQueue)
        val readyB = AtomicBoolean(false)
        coordinator.navigateToPlayer(1L, listOf(1L, 11L)) { throw IllegalStateException("boom") }
        coordinator.navigateToPlayer(2L, listOf(2L, 22L)) { readyB.set(true) }
        scheduler.advanceUntilIdle()
        assertTrue("consumer must keep serving the queue", readyB.get())
        assertEquals(listOf(2L, 22L), videoQueue.getIds())
    }

    @Test
    fun `cancellation in transaction terminates consumer and later registrations are no-op`() {
        val scheduler = TestCoroutineScheduler()
        val coordinator = QueueNavigationCoordinator(gatedStore(scheduler), videoQueue, imageQueue)
        val readyB = AtomicBoolean(false)
        coordinator.navigateToPlayer(1L, listOf(1L, 11L)) { throw CancellationException("simulated") }
        coordinator.navigateToPlayer(2L, listOf(2L, 22L)) { readyB.set(true) }
        scheduler.advanceUntilIdle()
        // CE 传播 → 消费者干净终止 + 通道 cancel → 积压事务丢弃。
        assertFalse(readyB.get())
        assertTrue(coordinator.navQueueDrainedForTest)
        // 通道关闭后的新登记：trySend 优雅 no-op，不崩、不导航。
        val readyC = AtomicBoolean(false)
        coordinator.navigateToPlayer(3L, listOf(3L, 33L)) { readyC.set(true) }
        scheduler.advanceUntilIdle()
        assertFalse(readyC.get())
    }

    // ---- P6 🔴1 LRU 热缓存类型旁路 -----------------------------------------

    @Test
    fun `image prewarm cannot leak into video restore via hot cache`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToImageViewer(5L, listOf(4L, 5L, 6L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        // 预热 LRU：IMAGE token 以 IMAGE 语义入缓存。
        cold.restoreImageQueue(
            SavedStateHandle(mapOf(QUEUE_IMAGE_TOKEN_KEY to tokenRef.get())), routeCurrentId = 5L
        )
        assertEquals(listOf(4L, 5L, 6L), imageQueue.getIds())
        // 同一 token 串到 VIDEO handle：复合键缺失下会热命中旁路类型校验；
        // 修复后必须降级为 route 单项（而非把 image 列表填入 videoQueue）。
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 4L
        )
        assertEquals(listOf(4L), videoQueue.getIds())
    }

    @Test
    fun `video prewarm cannot leak into image restore via hot cache`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokenRef = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        writer.navigateToPlayer(2L, listOf(1L, 2L, 3L)) { tokenRef.set(it); ready.set(true) }
        awaitReady(ready)

        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokenRef.get())), routeCurrentId = 2L
        )
        assertEquals(listOf(1L, 2L, 3L), videoQueue.getIds())
        cold.restoreImageQueue(
            SavedStateHandle(mapOf(QUEUE_IMAGE_TOKEN_KEY to tokenRef.get())), routeCurrentId = 1L
        )
        assertEquals(listOf(1L), imageQueue.getIds())
    }

    // ---- P6 附修：LRU 容量 5 + 轮换逐出 / route 降级登记语义 ----------------

    @Test
    fun `lru cache evicts rotated tokens so stale generations are re-resolved`() {
        val writer = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val cold = QueueNavigationCoordinator(store(), videoQueue, imageQueue)
        val tokens = mutableListOf<String>()
        val handles = mutableListOf<SavedStateHandle>()
        val ready = AtomicBoolean(false)
        val lists = listOf(
            listOf(100L, 101L), listOf(200L), listOf(300L),
            listOf(400L), listOf(500L, 501L), listOf(600L),
        )
        // 逐代次「准备 → 立即回填」：快照文件始终只持有最新代次，
        // 每个 token 仅在其为最新时才可从磁盘全量恢复。
        for (l in lists) {
            writer.navigateToPlayer(l.first(), l) { tokens.add(it); ready.set(true) }
            awaitReady(ready)
            ready.set(false)
            handles.add(SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokens.last())))
            cold.restoreVideoQueue(handles.last(), routeCurrentId = l.first())
            assertEquals("latest generation must restore full", l, videoQueue.getIds())
        }
        // LRU 容量 5：T1 已被 T2..T6 逐出。
        videoQueue.consumeIds(600L)
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokens[0])), routeCurrentId = 100L
        )
        assertEquals(
            "T1 must be evicted and re-resolved against the newer file (degraded to route item)",
            listOf(100L), videoQueue.getIds(),
        )
        // 对照：T5 仍在缓存 → 全量（若走磁盘会被 gen6 拒绝降级为 [500]）。
        cold.restoreVideoQueue(
            SavedStateHandle(mapOf(QUEUE_VIDEO_TOKEN_KEY to tokens[4])), routeCurrentId = 500L
        )
        assertEquals("T5 must still hit the warm cache", listOf(500L, 501L), videoQueue.getIds())
    }
}
