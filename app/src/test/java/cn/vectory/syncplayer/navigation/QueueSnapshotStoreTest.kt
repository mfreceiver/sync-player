package cn.vectory.syncplayer.navigation

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P3 队列快照存储测试：roundtrip、损坏降级、token 错配拒绝、旧代次不覆盖新、
 * 切换当前 id 不重写、大列表预算防御、两阶段提交 tmp 清理。
 * 全部使用注入临时目录 + 真实 IO dispatcher（JVM 纯单测）。
 */
class QueueSnapshotStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dir: File
    private val io: CoroutineDispatcher = Dispatchers.IO

    @Before
    fun setUp() {
        dir = tmpFolder.newFolder("queue_snapshots")
    }

    private fun store(maxIds: Int = QueueSnapshotStore.DEFAULT_MAX_IDS): QueueSnapshotStore =
        QueueSnapshotStore(dir, io, QueueSnapshotStore.DEFAULT_MAX_FILE_BYTES, maxIds)

    private fun videoFile(): File = File(dir, QueueMediaType.VIDEO.snapshotFileName)

    private fun imageFile(): File = File(dir, QueueMediaType.IMAGE.snapshotFileName)

    // ---- roundtrip -------------------------------------------------------

    @Test
    fun `write then restore roundtrip preserves order and token fields`() = runBlocking {
        // (P7 裁定 d) 同身份精确匹配接受回归：token.id == 文件.id → FULL 接受。
        val store = store()
        val ids = listOf(5L, 3L, 9L, 1L)
        val token = store.prepare(QueueMediaType.VIDEO, ids, currentId = 3L)

        val decoded = QueueToken.decode(token)!!
        assertEquals(QueueMediaType.VIDEO, decoded.type)
        assertEquals(QueueToken.Mode.FULL, decoded.mode)
        assertEquals(3L, decoded.currentId)
        assertTrue("snapshot id must be 32-hex", HEX32.matches(decoded.snapshotId))

        // 新 store 实例模拟进程重启后的冷恢复（无内存缓存，必须走磁盘）。
        val restored = store().readForRestore(token, QueueMediaType.VIDEO)
        assertEquals(ids, restored)
    }

    @Test
    fun `ten thousand ids roundtrip stays within 16MiB budget`() = runBlocking {
        val store = store()
        val ids = (1L..10_000L).toList()
        val token = store.prepare(QueueMediaType.VIDEO, ids, currentId = 7_777L)

        assertTrue(videoFile().isFile)
        val bytes = videoFile().length()
        assertTrue("snapshot should be compact, was $bytes", bytes in 1 until 16L * 1024 * 1024)

        val restored = store().readForRestore(token, QueueMediaType.VIDEO)
        assertEquals(ids, restored)
    }

    @Test
    fun `image queue roundtrip uses separate file`() = runBlocking {
        val store = store()
        val token = store.prepare(QueueMediaType.IMAGE, listOf(4L, 5L, 6L), currentId = 5L)
        assertEquals(QueueMediaType.IMAGE, QueueToken.decode(token)!!.type)
        assertTrue(imageFile().isFile)
        assertFalse(videoFile().exists())
        assertEquals(listOf(4L, 5L, 6L), store().readForRestore(token, QueueMediaType.IMAGE))
    }

    // ---- 损坏 / 半写检测 -------------------------------------------------

    @Test
    fun `bit flip in snapshot body degrades to null`() = runBlocking {
        val store = store()
        val token = store.prepare(QueueMediaType.VIDEO, listOf(1L, 2L, 3L), currentId = 2L)
        val bytes = videoFile().readBytes()
        bytes[bytes.size / 4] = (bytes[bytes.size / 4].toInt() xor 0x5A).toByte()
        videoFile().writeBytes(bytes)
        assertNull(store().readForRestore(token, QueueMediaType.VIDEO))
    }

    @Test
    fun `truncated half-written snapshot degrades to null`() = runBlocking {
        val store = store()
        val token = store.prepare(QueueMediaType.VIDEO, listOf(1L, 2L, 3L), currentId = 2L)
        val bytes = videoFile().readBytes()
        videoFile().writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(store().readForRestore(token, QueueMediaType.VIDEO))
    }

    @Test
    fun `garbage snapshot file degrades to null`() = runBlocking {
        videoFile().writeText("this is not a snapshot\nat all\n")
        val token = "QSNAP2|video|" + "ab".repeat(16) + "|1|full"
        assertNull(store().readForRestore(token, QueueMediaType.VIDEO))
    }

    @Test
    fun `missing snapshot file degrades to null`() = runBlocking {
        assertNull(store().readForRestore("QSNAP2|video|" + "ab".repeat(16) + "|1|full", QueueMediaType.VIDEO))
    }

    // ---- token 错配拒绝 --------------------------------------------------

    @Test
    fun `old identity token degrades while new identity accepts after successive writes`() = runBlocking {
        // (P7 裁定 c) 两 token 不同 id 先后写入：旧 id 恢复拒绝（store 层 null，
        // 调用方降级 route 单项），新 id 接受。
        val store = store()
        val old = store.prepare(QueueMediaType.VIDEO, listOf(1L, 2L, 3L), currentId = 1L)
        val new = store.prepare(QueueMediaType.VIDEO, listOf(9L, 8L, 7L), currentId = 8L)
        assertTrue(
            "identities must be unique",
            QueueToken.decode(old)!!.snapshotId != QueueToken.decode(new)!!.snapshotId,
        )

        val cold = store()
        assertEquals(listOf(9L, 8L, 7L), cold.readForRestore(new, QueueMediaType.VIDEO))
        assertNull("old-identity token must not bind the newer file", cold.readForRestore(old, QueueMediaType.VIDEO))
    }

    @Test
    fun `token with current id absent from snapshot is rejected`() = runBlocking {
        val store = store()
        val token = store.prepare(QueueMediaType.VIDEO, listOf(1L, 2L, 3L), currentId = 2L)
        val forged = QueueToken(
            QueueMediaType.VIDEO,
            snapshotId = QueueToken.decode(token)!!.snapshotId,
            currentId = 99L,
            mode = QueueToken.Mode.FULL,
        ).encode()
        assertNull(store().readForRestore(forged, QueueMediaType.VIDEO))
    }

    @Test
    fun `cross type token is rejected by explicit expected type`() = runBlocking {
        val store = store()
        val videoToken = store.prepare(QueueMediaType.VIDEO, listOf(1L, 2L, 3L), currentId = 2L)
        val imageToken = store.prepare(QueueMediaType.IMAGE, listOf(4L, 5L, 6L), currentId = 5L)
        // 🟠3 显式类型绑定：错型 token 一律拒绝，即便同代次文件真实存在。
        assertNull(store().readForRestore(videoToken, QueueMediaType.IMAGE))
        assertNull(store().readForRestore(imageToken, QueueMediaType.VIDEO))
    }

    @Test
    fun `malformed tokens never decode`() {
        assertNull(QueueToken.decode(null))
        assertNull(QueueToken.decode(""))
        assertNull(QueueToken.decode("garbage"))
        val id = "ab".repeat(16)
        assertNull(QueueToken.decode("QSNAP1|$id|1|full"))        // 旧版本前缀
        assertNull(QueueToken.decode("QSNAP2|audio|$id|1|full"))   // 未知类型
        assertNull(QueueToken.decode("QSNAP2|video|NOT-HEX|1|full")) // 身份格式非法
        assertNull(QueueToken.decode("QSNAP2|video|abc|1|full"))   // 身份过短
        assertNull(QueueToken.decode("QSNAP2|video|$id|abc|full")) // 当前项非数字
        assertNull(QueueToken.decode("QSNAP2|video|$id|1|weird"))  // 未知模式
    }

    // ---- 旧代次不覆盖新（并发写串行化） ----------------------------------

    @Test
    fun `concurrent writes leave exactly the on-disk identity resolvable`() =
        runBlocking {
            val store = store()
            val first = async {
                store.prepare(QueueMediaType.VIDEO, (1L..500L).toList(), currentId = 1L)
            }
            val second = async {
                store.prepare(QueueMediaType.VIDEO, (1_000L..2_000L).toList(), currentId = 1_500L)
            }
            val tokens = listOf(first.await(), second.await()).map { QueueToken.decode(it)!! }
            assertTrue("identities must be unique", tokens[0].snapshotId != tokens[1].snapshotId)

            // 文件只持有后写入者的身份：恰好一个 token 可全量恢复，另一个拒绝。
            val cold = store()
            val resolved = tokens.mapNotNull { cold.readForRestore(it.encode(), QueueMediaType.VIDEO) }
            assertEquals("exactly the on-disk identity may resolve", 1, resolved.size)
        }

    // ---- 切换当前 id：复用快照不重写 --------------------------------------

    @Test
    fun `switching current id on same list reuses snapshot without rewrite`() = runBlocking {
        val store = store()
        val ids = listOf(10L, 20L, 30L, 40L)
        val tokenA = store.prepare(QueueMediaType.VIDEO, ids, currentId = 10L)
        val bytesA = videoFile().readBytes()

        val tokenB = store.prepare(QueueMediaType.VIDEO, ids, currentId = 40L)

        val decodedB = QueueToken.decode(tokenB)!!
        assertEquals(QueueToken.Mode.FULL, decodedB.mode)
        assertEquals(40L, decodedB.currentId)
        assertEquals(
            "same list must reuse the on-disk identity",
            QueueToken.decode(tokenA)!!.snapshotId,
            decodedB.snapshotId,
        )
        assertArrayEquals("snapshot file must not be rewritten", bytesA, videoFile().readBytes())

        val cold = store()
        assertEquals(ids, cold.readForRestore(tokenB, QueueMediaType.VIDEO))
        // 旧 token（同代次、旧当前项）仍与其列表自洽，同样可恢复完整列表。
        assertEquals(ids, cold.readForRestore(tokenA, QueueMediaType.VIDEO))
    }

    // ---- 大列表预算防御 / 降级 --------------------------------------------

    @Test
    fun `oversized list rejects snapshot and degrades to single item token`() = runBlocking {
        val store = store(maxIds = 10)
        val ids = (1L..20L).toList()
        val token = store.prepare(QueueMediaType.VIDEO, ids, currentId = 15L)

        val decoded = QueueToken.decode(token)!!
        assertEquals(QueueToken.Mode.SINGLE, decoded.mode)
        assertEquals(15L, decoded.currentId)
        assertFalse("rejected snapshot must not leave a file behind", videoFile().exists())

        assertEquals(listOf(15L), store().readForRestore(token, QueueMediaType.VIDEO))
    }

    @Test
    fun `payload exceeding file byte budget degrades to single item`() = runBlocking {
        // maxIds 放行，但把字节预算压到极小以触发 payload 体积拒绝分支。
        val store = QueueSnapshotStore(dir, io, maxFileBytes = 32, maxIds = QueueSnapshotStore.DEFAULT_MAX_IDS)
        val ids = (1L..100L).toList()
        val token = store.prepare(QueueMediaType.VIDEO, ids, currentId = 5L)
        assertEquals(QueueToken.Mode.SINGLE, QueueToken.decode(token)!!.mode)
        assertFalse(videoFile().exists())
    }

    @Test
    fun `single mode token restores current item without any file`() = runBlocking {
        val token = "QSNAP2|video|" + "ab".repeat(16) + "|77|single"
        assertEquals(listOf(77L), store().readForRestore(token, QueueMediaType.VIDEO))
    }

    @Test
    fun `empty id list normalizes to single item full snapshot`() = runBlocking {
        // 空列表在 store 内规范为 [currentId]：恢复语义即真实的单项队列。
        val store = store()
        val token = store.prepare(QueueMediaType.VIDEO, emptyList(), currentId = 7L)
        assertEquals(QueueToken.Mode.FULL, QueueToken.decode(token)!!.mode)
        assertEquals(7L, QueueToken.decode(token)!!.currentId)
        assertEquals(listOf(7L), store().readForRestore(token, QueueMediaType.VIDEO))
    }

    // ---- 两阶段提交：tmp 卫生 / 恢复后代次下限 -----------------------------

    @Test
    fun `stale tmp file from crashed write is removed by next write`() = runBlocking {
        val staleTmp = File(dir, QueueMediaType.VIDEO.snapshotFileName + ".tmp")
        staleTmp.writeText("half written crash leftovers")

        val store = store()
        val token = store.prepare(QueueMediaType.VIDEO, listOf(1L, 2L), currentId = 1L)

        assertFalse(staleTmp.exists())
        assertTrue(videoFile().isFile)
        assertEquals(listOf(1L, 2L), store().readForRestore(token, QueueMediaType.VIDEO))
    }

    @Test
    fun `lost snapshot file then new write cannot be hijacked by surviving old token`() = runBlocking {
        // (P7 裁定 a / reviewer 反例原文场景)：Store A 写 VIDEO[1,2] 签 token A →
        // 快照文件丢失、token A 尚存 → 新 Store B（身份状态全新）先 prepare 新
        // 队列 [1,3] → 再恢复旧 token A：身份不等 → 拒绝误绑定 [1,3]
        //（store 层 null，协调层降级 route 单项）。
        val first = store()
        val oldToken = first.prepare(QueueMediaType.VIDEO, listOf(1L, 2L), currentId = 1L)
        assertTrue(videoFile().delete())

        val second = store()
        val newToken = second.prepare(QueueMediaType.VIDEO, listOf(1L, 3L), currentId = 1L)

        val cold = store()
        assertNull(cold.readForRestore(oldToken, QueueMediaType.VIDEO))
        assertEquals(listOf(1L, 3L), cold.readForRestore(newToken, QueueMediaType.VIDEO))
    }

    // ---- P4 🔴1 / P7 唯一身份：不依赖先恢复的覆盖写入 ----------------------

    @Test
    fun `fresh store overwrite without prior restore rejects old token`() = runBlocking {
        val first = store()
        val oldToken = first.prepare(QueueMediaType.VIDEO, listOf(1L, 2L, 3L), currentId = 1L)

        // 全新 Store 实例（模拟进程重启）不调 readForRestore，直接覆盖同类型文件：
        // 新写入签发全新随机身份，旧 token 的身份与文件身份必不相等 → 被拒。
        val second = store()
        val newToken = second.prepare(QueueMediaType.VIDEO, listOf(9L, 9L), currentId = 9L)
        assertTrue(
            "identities must be unique",
            QueueToken.decode(oldToken)!!.snapshotId != QueueToken.decode(newToken)!!.snapshotId,
        )

        val cold = store()
        assertNull(cold.readForRestore(oldToken, QueueMediaType.VIDEO))
        assertEquals(listOf(9L, 9L), cold.readForRestore(newToken, QueueMediaType.VIDEO))
    }

    // ---- P4 🟠5 恢复异常兜底 ----------------------------------------------

    @Test
    fun `snapshot path being a directory degrades restore to null`() = runBlocking {
        assertTrue(videoFile().mkdirs())
        assertNull(store().readForRestore("QSNAP2|video|" + "ab".repeat(16) + "|1|full", QueueMediaType.VIDEO))
        Unit
    }

    @Test
    fun `unreadable snapshot file degrades restore to null when enforceable`() = runBlocking {
        val f = videoFile()
        f.writeText("SPQ1\ngen=1\ncount=1\n1\n")
        assumeTrue("fixture must drop content read permission", f.setReadable(false, false))
        try {
            assumeTrue("content still readable (privileged env) — case not meaningful", !f.canRead())
            assertNull(store().readForRestore("QSNAP2|video|" + "ab".repeat(16) + "|1|full", QueueMediaType.VIDEO))
        } finally {
            f.setReadable(true, true)
        }
        Unit
    }

    // ---- P5 🟠2 代次语义 --------------------------------------------------

    @Test
    fun `consecutive prepares sign pairwise-distinct identities`() = runBlocking {
        // 唯一身份协议：连续两次 prepare 签发的身份互不相同且均为 32-hex。
        val store = store()
        val t1 = store.prepare(QueueMediaType.VIDEO, listOf(1L), currentId = 1L)
        val t2 = store.prepare(QueueMediaType.VIDEO, listOf(2L), currentId = 2L)
        val id1 = QueueToken.decode(t1)!!.snapshotId
        val id2 = QueueToken.decode(t2)!!.snapshotId
        assertTrue(HEX32.matches(id1))
        assertTrue(HEX32.matches(id2))
        assertTrue(id1 != id2)
    }

    @Test
    fun `corrupt file yields unknown identity so restore rejects and new write recovers`() = runBlocking {
        // (P7 裁定 b)：损坏文件 → id 读不出（身份未知）→ 恢复拒绝（协调层降级
        // route 单项）；唯一身份协议下，覆盖损坏文件的新写入签发全新随机 id，
        // 直接恢复正常 FULL 语义（无身份重用面）。
        val token = store().prepare(QueueMediaType.VIDEO, listOf(1L, 2L), currentId = 1L)
        videoFile().writeText("garbage — not a snapshot")
        assertNull(store().readForRestore(token, QueueMediaType.VIDEO))

        val recovered = store().prepare(QueueMediaType.VIDEO, listOf(5L, 6L), currentId = 5L)
        assertEquals(QueueToken.Mode.FULL, QueueToken.decode(recovered)!!.mode)
        assertEquals(listOf(5L, 6L), store().readForRestore(recovered, QueueMediaType.VIDEO))
    }

    companion object {
        private val HEX32 = Regex("[0-9a-f]{32}")
    }
}
