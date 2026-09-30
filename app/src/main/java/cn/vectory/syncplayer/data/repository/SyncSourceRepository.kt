package cn.vectory.syncplayer.data.repository

import android.annotation.SuppressLint
import android.util.Log
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.syncplayer.data.local.AppDatabase
import cn.vectory.syncplayer.data.local.dao.SyncSourceDao
import cn.vectory.syncplayer.data.local.entity.SyncSourceEntity
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭证状态不确定（补偿部分失败）：读取 fail-closed，重新 save 成功后解除。
 * 消息只含 id，不含密码/host/底层 message。
 *
 * 边界（如实声明）：这是**进程内**标记，不声称跨进程持久；跨存储崩溃原子性
 * （prefs + DB 同时成功或同时失败）本仓不做保证——进程在补偿中途崩溃后，
 * 下次读取可能拿到**缺失或不匹配**的凭证（不只是旧值：例如 DB 已回滚而
 * prefs 磁盘已落新值的不匹配态）。若需要更强的持久修复契约（如 journal/wal），
 * 应作为独立设计上报，而非在本层内发明。
 */
class SyncSourceCredentialUncertainException(val sourceId: Long) :
    RuntimeException("Credential state uncertain for source id=$sourceId; re-save the source to recover")

@Singleton
class SyncSourceRepository @Inject constructor(
    private val syncSourceDao: SyncSourceDao,
    private val masterKey: MasterKey,
    private val encryptedPrefs: EncryptedSharedPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val appDatabase: AppDatabase
) {
    /** Serializes save()/delete()/credential-read operations: they mutate or observe
     *  the same (DB row, `pw_<id>` pref) pair, so they must serialize. Saves are not
     *  a hot path, so a single mutex is sufficient and avoids per-id map cleanup. */
    private val saveMutex = Mutex()

    /** 进程内 fail-closed 标记：补偿部分失败后，该 id 的凭证不可信。 */
    private val uncertainCredentials = ConcurrentHashMap.newKeySet<Long>()

    fun getAllFlow(): Flow<List<SyncSource>> =
        syncSourceDao.getAllFlow().map { list -> list.map { it.toInfo() } }

    suspend fun getAll(): List<SyncSource> =
        syncSourceDao.getAll().map { it.toInfo() }

    // 凭证读取与 save/delete 共享互斥锁：避免读到「旧行 + 新密码」的撕裂组合。
    // 阻塞的 EncryptedSharedPreferences 读运行在注入的 IO dispatcher 上。
    suspend fun getAllWithCredentials(): List<SyncSource> = saveMutex.withLock {
        withContext(ioDispatcher) {
            val entities = syncSourceDao.getAll()
            entities.firstOrNull { it.id in uncertainCredentials }
                ?.let { throw SyncSourceCredentialUncertainException(it.id) }
            entities.map { it.toDomainWithCredentials() }
        }
    }

    suspend fun getById(id: Long): SyncSource? = saveMutex.withLock {
        withContext(ioDispatcher) {
            if (id in uncertainCredentials) throw SyncSourceCredentialUncertainException(id)
            syncSourceDao.getById(id)?.toDomainWithCredentials()
        }
    }

    suspend fun getRelationCount(sourceId: Long): Int =
        syncSourceDao.getRelationCount(sourceId)

    // commit() (not apply()) is a deliberate contract: the encrypted password must be
    // synchronously durable before the DB row references it, and the boolean result
    // drives rollback (restore previous password / delete the new row).
    @SuppressLint("ApplySharedPref")
    suspend fun save(source: SyncSource): Long = saveMutex.withLock {
        // 已取消的调用方拿到锁也不得继续（CE 优先，避免在取消后制造副作用）
        currentCoroutineContext().ensureActive()
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
            // Existing source: snapshot old password FIRST (pure read, outside the
            // consistency interval — cancellation here = zero side effects).
            val prevPassword = withContext(ioDispatcher) {
                encryptedPrefs.getString("pw_${source.id}", null)
            }
            currentCoroutineContext().ensureActive()
            // 窄 NonCancellable 一致性区间：prefs commit + DB upsert + 补偿 = 一个
            // 本地跨存储单元，**唯一恢复入口**（单 try/catch，避免双补偿）。
            // commit false / throws / DAO 失败（含 DAO 主动 CE）都走同一补偿并
            // 保留主异常对象；区间外取消不打断在途提交、不回滚已提交结果，
            // CE 在区间结束后的下一个挂起点（下方 ensureActive）正常传播。
            withContext(NonCancellable + ioDispatcher) {
                try {
                    val prefsOk = encryptedPrefs.edit()
                        .putString("pw_${source.id}", source.password)
                        .commit()
                    if (!prefsOk) {
                        // commit() false 前可能已改内存态：false 转主异常，统一恢复
                        throw RuntimeException(
                            "Failed to write encrypted password for source ${source.id}"
                        )
                    }
                    syncSourceDao.upsert(entity)
                } catch (caught: Throwable) {
                    // C8：补偿信息必须挂在**原主异常**上。跨协程边界（Room 事务/NC/IO）
                    // 可能把主异常恢复为同类型副本；只把 suppressed 加在副本上，外层
                    // 再次恢复后副本不可达、补偿诊断丢失。先规范化再补偿/重抛。
                    val main = unwrapCoroutineRecovery(caught)
                    restorePreviousPassword(source.id, prevPassword, main)
                    throw main
                }
                // 全部提交成功后才清除不确定标记：在 NC 区间内执行，
                // 区间外（下方 ensureActive）的取消不会跳过清理。
                uncertainCredentials.remove(source.id)
            }
            currentCoroutineContext().ensureActive() // 提交后取消 → CE，不回滚
            source.id
        } else {
            // New source (id=0): DB first to get the auto-generated ID, then prefs.
            // 同样的窄一致性区间 + 唯一补偿入口（新源：清键 + 回滚行）。
            val savedId = withContext(NonCancellable + ioDispatcher) {
                val savedId = syncSourceDao.upsert(entity)
                try {
                    val prefsOk = encryptedPrefs.edit()
                        .putString("pw_$savedId", source.password)
                        .commit()
                    if (!prefsOk) {
                        throw RuntimeException(
                            "Failed to write encrypted password for new source $savedId"
                        )
                    }
                } catch (caught: Throwable) {
                    // C8：同上——补偿前规范化到原主异常（未来 DAO 真挂起抛错也会跨恢复边界）。
                    val main = unwrapCoroutineRecovery(caught)
                    rollbackNewSource(savedId, main)
                    throw main
                }
                uncertainCredentials.remove(savedId)
                savedId
            }
            currentCoroutineContext().ensureActive()
            savedId
        }
    }

    /**
     * Shares [saveMutex] with [save] on purpose. Guarding only save/save is not enough — a
     * save/delete interleaving can leave the DB row alive with its password removed (delete
     * wipes prefs between save's prefs-write and DB-upsert), or resurrect a deleted source.
     * Both operations mutate the same (DB row, `pw_<id>` pref) pair, so they must serialize.
     */
    // commit() is deliberate: credential removal must be durable alongside the DB delete;
    // apply()'s async write could outlive process death and leave an orphaned password.
    //
    // C2: DB 删除 + 凭证移除收敛进真实 Room 事务 —— prefs commit 失败在事务内抛错，
    // Room 回滚保留源行（不永久丢源行）；事务失败时统一恢复旧凭证。
    // sync_relation 的 FK 为 RESTRICT（非 CASCADE）：被引用源行删除会被数据库拒绝，
    // 关系行天然保留，无需（也不做）已删除关联的恢复。
    @SuppressLint("ApplySharedPref")
    suspend fun delete(id: Long) = saveMutex.withLock {
        currentCoroutineContext().ensureActive()
        val prevPassword = withContext(ioDispatcher) {
            encryptedPrefs.getString("pw_$id", null)
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + ioDispatcher) {
            try {
                appDatabase.withTransaction {
                    syncSourceDao.deleteById(id)
                    val removed = encryptedPrefs.edit().remove("pw_$id").commit()
                    if (!removed) {
                        throw RuntimeException(
                            "Failed to remove encrypted password for source $id"
                        )
                    }
                }
            } catch (caught: Throwable) {
                // 事务已回滚（源行保留）或删除本身失败：remove 可能已改内存态，
                // 统一恢复旧凭证；补偿失败记为 suppressed（自抑制保护）+ 标记
                // 不确定，不掩盖主错误。
                // C8：withTransaction 跨协程边界会把主异常恢复为副本 B（cause=A），
                // 外层 NC/IO 边界会再恢复一次沿 A 生成 C —— 只挂在 B 上的补偿
                // 信息会丢失。规范化到 A 后补偿，suppressed 随 A 存活。
                val main = unwrapCoroutineRecovery(caught)
                restorePreviousPassword(id, prevPassword, main)
                throw main
            }
            // 全部提交成功后才清除不确定标记（NC 内，取消不会跳过清理）
            uncertainCredentials.remove(id)
        }
        currentCoroutineContext().ensureActive()
        Unit
    }

    /**
     * 补偿：恢复 [id] 的旧凭证（有旧值 → 写回；无旧值 → remove 键）。
     * 补偿各步独立尝试：失败记为 [main] 的 suppressed（程序可观察性，日志只是补充），
     * 并将该 id 标记为凭证状态不确定（读取 fail-closed）。
     */
    @SuppressLint("ApplySharedPref")
    private suspend fun restorePreviousPassword(id: Long, prev: String?, main: Throwable) {
        try {
            val restored = encryptedPrefs.edit().apply {
                if (prev != null) putString("pw_$id", prev) else remove("pw_$id")
            }.commit()
            if (!restored) {
                main.addSuppressed(
                    RuntimeException("credential restore commit failed for source id=$id")
                )
                markUncertain(id)
            }
        } catch (t: Throwable) {
            // 自抑制保护：补偿异常与主异常同对象时不可 addSuppressed（会 IAE）
            if (t !== main) main.addSuppressed(t)
            markUncertain(id)
        }
    }

    /**
     * 新源补偿：清新键 + 回滚 DB 行（各自独立尝试；失败 suppressed；行回滚失败 ⇒
     * 该 id 凭证状态不确定）。主错误始终保留。
     */
    @SuppressLint("ApplySharedPref")
    private suspend fun rollbackNewSource(savedId: Long, main: Throwable) {
        try {
            val cleared = encryptedPrefs.edit().remove("pw_$savedId").commit()
            if (!cleared) {
                main.addSuppressed(
                    RuntimeException("credential cleanup commit failed for source id=$savedId")
                )
            }
        } catch (t: Throwable) {
            if (t !== main) main.addSuppressed(t)
        }
        var rowRolledBack = true
        try {
            syncSourceDao.deleteById(savedId)
        } catch (t: Throwable) {
            if (t !== main) main.addSuppressed(t)
            rowRolledBack = false
        }
        if (!rowRolledBack) markUncertain(savedId)
    }

    private fun markUncertain(id: Long) {
        // 状态先行：即使日志故障也必须先完成标记，不得因诊断失败跳过 fail-closed。
        uncertainCredentials.add(id)
        try {
            Log.w(TAG, "Credential state marked uncertain for source id=$id")
        } catch (t: Throwable) {
            // 诊断边界：best-effort 日志故障不得逃逸进入补偿调用方遮盖主异常。
            // 此处只包裹非挂起的 Log 调用，不经过也不吞任何业务 CE。
        }
    }

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

/**
 * C8：剥离协程 stacktrace recovery 生成的**框架副本层**，返回其保留的原主异常。
 *
 * 只在以下条件全部成立时剥一层：当前层与 cause **同运行时类型**、当前层堆栈含
 * 精确恢复帧（`_COROUTINE._BOUNDARY` / `_` / `CoroutineDebugging.kt`，不绑行号）、
 * 当前层 suppressed 为空（不静默丢弃已有诊断）。无 cause / 类型不同 / 无标识 /
 * 已有 suppressed → 停在当前层；cause 图成环 → 返回原输入。不比较 message、
 * 不无条件取 root cause、不吞业务 wrapper（同类型普通业务包装因无恢复帧被保留）。
 *
 * 兼容边界（非安全边界）：识别依赖当前协程版本的恢复格式；kotlinx.coroutines
 * 依赖升级后应由 `SyncSourceRepositoryTest` 第 10 节回归重新验证。
 */
internal fun unwrapCoroutineRecovery(error: Throwable): Throwable {
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    var current = error
    while (true) {
        // 异常图异常（成环等）时保守返回输入：不无限循环，也不任意选根。
        if (seen.put(current, true) != null) return error
        val cause = current.cause ?: return current
        if (cause.javaClass != current.javaClass) return current
        val hasRecoveryBoundary = current.stackTrace.any {
            it.className == "_COROUTINE._BOUNDARY" &&
                it.methodName == "_" &&
                it.fileName == "CoroutineDebugging.kt"
        }
        if (!hasRecoveryBoundary) return current
        // 需要剥离的框架外层均为空 suppressed；非空说明该层承载诊断，不丢。
        if (current.suppressed.isNotEmpty()) return current
        if (seen.containsKey(cause)) return error
        current = cause
    }
}
