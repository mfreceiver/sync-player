package cn.vectory.syncplayer.data.repository

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * C3/C5 共享测试 fake：EncryptedSharedPreferences 的有状态替身（仅测试源码）。
 *
 * **边界声明（勿冒称）**：本 fake 只建模 SyncSourceRepository 的「互斥锁串行」
 * 调用协议——同一时刻至多一个 editor 在途（由 repo 的 saveMutex 保证）。它
 * **不是**通用并行多-editor 线程安全实现：ConcurrentHashMap 仅保证单 map 的
 * 并发读写字典安全，pending/edit 流程本身无跨线程协议，换 concurrent 队列也
 * 不代表整个 fake 线程安全。
 *
 * 关键语义（与真实 SharedPreferences 的关键子集一致，不冒充全量真机验证）：
 * - 每个 edit() 返回**独立 editor**，各自持 pending map；put/remove 只改 pending
 *   （edit 边界即 pending 批次，事件序列不单列 edit 令牌）；
 * - commit() **先把 pending 应用到 memory**（真实实现可能先改内存态），
 *   返回 true 才把 memory 快照 copy 到 disk；false ⇒ memory 已变、disk 保持旧值；
 *   脚本为 Throwable ⇒ 在应用 memory **之后**抛出（"明确发生阶段"：内存已变后
 *   throws）；同一 Throwable 实例可重复入队（供自抑制测试）；
 * - 事件全序（get:/put:k=v/remove:k/commit:true|false|throw）+ 线程记录 + 最终
 *   memory/disk 双态，供双重验收。
 *
 * 脚本队列 commitScript：true / false / Throwable；空 = 恒 true；按消费先后出队。
 */
class StatefulPrefsFake(initial: Map<String, String> = emptyMap()) {

    val disk: ConcurrentHashMap<String, String> = ConcurrentHashMap(initial)
    val memory: ConcurrentHashMap<String, String> = ConcurrentHashMap(initial)
    val events: MutableList<String> = CopyOnWriteArrayList()
    val threads: MutableList<Thread> = CopyOnWriteArrayList()
    val commitScript = ArrayDeque<Any>()
    var getStringCalls = 0
        private set

    /** 快照门：getString 到达时先 complete（供测试挂起等待，无自旋），再阻塞在 gate。 */
    var snapshotReached: CompletableDeferred<Unit>? = null
    var snapshotGate: CountDownLatch? = null

    private class PendingEdit(val fake: StatefulPrefsFake) {
        val pending = LinkedHashMap<String, String?>() // null = remove

        fun put(key: String, value: String) {
            pending[key] = value
            fake.events.add("put:$key=$value")
        }

        fun remove(key: String) {
            pending[key] = null
            fake.events.add("remove:$key")
        }

        fun commit(): Boolean {
            // 先应用到 memory（总是）
            for ((k, v) in pending) {
                if (v == null) fake.memory.remove(k) else fake.memory[k] = v
            }
            val scripted = if (fake.commitScript.isEmpty()) true else fake.commitScript.removeFirst()
            if (scripted is Throwable) {
                fake.events.add("commit:throw")
                throw scripted
            }
            val ok = scripted as Boolean
            if (ok) {
                fake.disk.clear()
                fake.disk.putAll(fake.memory)
            }
            fake.events.add("commit:$ok")
            return ok
        }
    }

    /** 每次 edit() 一个精确 editor mock（独立 pending），挂到本 fake。 */
    fun newEditorMock(): SharedPreferences.Editor {
        val pending = PendingEdit(this)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            threads.add(Thread.currentThread())
            pending.put(firstArg(), secondArg())
            editor
        }
        every { editor.remove(any()) } answers {
            threads.add(Thread.currentThread())
            pending.remove(firstArg())
            editor
        }
        every { editor.commit() } answers {
            threads.add(Thread.currentThread())
            pending.commit()
        }
        return editor
    }

    fun getString(key: String, def: String?): String? {
        threads.add(Thread.currentThread())
        getStringCalls += 1
        events.add("get:$key")
        snapshotReached?.complete(Unit)
        snapshotGate?.let {
            it.await()
            snapshotGate = null
        }
        return memory[key] ?: def
    }

    fun getStringThreadAssert(mainThread: Thread) {
        // 验收：凭证读不在测试主线程（注入 dispatcher 语义）
        threads.forEach { check(it !== mainThread) { "credential read ran on main thread" } }
    }
}
