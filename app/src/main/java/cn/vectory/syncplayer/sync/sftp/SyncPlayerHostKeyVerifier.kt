package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import android.util.Log
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
import java.security.PublicKey
import java.util.Properties
import java.util.UUID

/**
 * SFTP host key verifier using Trust-On-First-Use (TOFU).
 *
 * [verify] 的写入路径（首次连接保存主机指纹）受进程级锁保护，
 * 确保多个 [SyncPlayerHostKeyVerifier] 实例并发写入 ([SftpFileClient]
 * 每次连接新建实例) 时不会丢失 [known_hosts.properties] 条目。
 */
class SyncPlayerHostKeyVerifier(context: Context) : HostKeyVerifier {
    private val hostsFile = File(context.filesDir, "known_hosts.properties")
    private val props = Properties()

    init {
        if (hostsFile.exists()) {
            try {
                hostsFile.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                Log.e("HostKeyVerifier", "Failed to load known_hosts", e)
            }
        }
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
        val keyLabel = "$hostname:$port"
        val savedFingerprint = props.getProperty(keyLabel)

        return if (savedFingerprint == null) {
            // Trust on first use — 整个 load → set → write 序列在进程级锁下执行，
            // 防止多个 verifier 实例并发写入时丢失条目。
            synchronized(lock) {
                // 重新从磁盘加载，纳入之前其他实例写入的变更
                reloadLocked()
                val rechecked = props.getProperty(keyLabel)
                if (rechecked != null) {
                    // 本锁期间其他实例已写入，直接比对
                    rechecked == fingerprint
                } else {
                    // AND-085: hostname/fingerprint stay out of logcat (red-line: no path/host in logs).
                    Log.i("HostKeyVerifier", "TOFU: first-time host key persisted")
                    props.setProperty(keyLabel, fingerprint)
                    try {
                        savePropsLocked()
                    } catch (e: Exception) {
                        // 保存失败（TOFU 未持久化）即不建立信任：回滚内存记录并重抛。
                        // 若不回滚，本实例后续 verify 会从 props 读到残留指纹直接放行，
                        // 从此不再尝试持久化——「保存失败即拒绝信任」的 TOFU 语义被破坏。
                        props.remove(keyLabel)
                        throw e
                    }
                    true
                }
            }
        } else {
            if (savedFingerprint == fingerprint) {
                true
            } else {
                Log.e("HostKeyVerifier", "Host key mismatch — rejecting connection")
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return emptyList()
    }

    /** 在锁内重新加载 [known_hosts.properties] 全量内容。 */
    private fun reloadLocked() {
        props.clear()
        if (hostsFile.exists()) {
            try {
                hostsFile.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                Log.e("HostKeyVerifier", "Failed to reload known_hosts under lock", e)
            }
        }
    }

    /**
     * 在锁内执行原子替换写入。
     * 临时文件名带唯一后缀，避免多个实例写同一临时文件。
     * 任一失败路径（[Properties.store] 抛异常 / [File.renameTo] 失败 / 其他异常）：
     * 先删除残留临时文件再重抛异常，使调用方感知 TOFU 未持久化，且不遗留 `.tmp` 孤儿。
     */
    private fun savePropsLocked() {
        val tmpFile = File(hostsFile.parentFile, "${hostsFile.name}.${UUID.randomUUID()}.tmp")
        try {
            tmpFile.outputStream().use { props.store(it, "SyncPlayer SFTP Known Hosts") }
            if (!tmpFile.renameTo(hostsFile)) {
                throw IOException("Failed to atomically replace known_hosts (rename to ${hostsFile.name} failed)")
            }
        } catch (e: Exception) {
            // 统一清理后重抛；重抛保证异常（含 CancellationException）照常传播，不被吞。
            tmpFile.delete()
            throw e
        }
    }

    companion object {
        /** 进程级锁，所有 [SyncPlayerHostKeyVerifier] 实例共享。 */
        private val lock = Any()
    }
}
