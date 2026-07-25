# 加密媒体存储与外置目录支持 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development (recommended) or ocmar-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 sync-player 支持把加密媒体写入用户指定目录（含外置 SD 卡），文件内容用 AES-CTR 加密、磁盘文件名用不透明 ID 命名，真名只在 Room DB；播放/取帧/缩略图/分析/分享经单一 ContentProvider 解密网关透明读取。

**Architecture:** 两个核心抽象——`MediaVolume`（屏蔽 File/SAF 存储后端）与 `MediaContentProvider`（用 `ProxyFileDescriptorCallback` 把"按 offset 就地 AES-CTR 解密"包成可 seek 的 `content://` URI）。`EncryptedMediaStore` 编排二者。明文只存在于内存与内部 Room DB；外置卷只存密文。

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, Room 2.7.0, Media3 1.6.0, Hilt 2.54, javax.crypto + BouncyCastle（已有）, SAF DocumentFile, Android Keystore, Coil 2.7.0。

## Global Constraints

- 构建命令统一加前缀 `JAVA_HOME=/home/mar/android-studio/jbr`；无 Kotlin LSP，**每个任务结束跑 `./gradlew compileDebugKotlin` 验证编译**（AGENTS.md）。
- 测试栈：JUnit 4 + MockK + Coroutines Test（`runTest`/`StandardTestDispatcher`/`Dispatchers.setMain`），样例见 `app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineTest.kt`。
- 加密参数（逐字取自修订后的 spec §5）：正文 AES-CTR，counter block = `IV(8B) ‖ counter(8B 大端)`，`counter = byteOffset/16`；磁盘纯密文无 header；缩略图 AES-GCM，每次写入随机 12B IV，文件格式为 `ver(1B)‖iv(12B)‖ciphertext+tag`。
- 威胁模型（逐字取自 spec §2）：仅防"设备离手/锁屏后被 USB/文件管理器读取"；**不**防 Root/取证/运行中翻看；密钥**不**绑生物识别。
- 磁盘命名（逐字取自 spec §5.3）：媒体 `<volumeRoot>/<id/1000>/<id>.enc`；缩略图 `<volumeRoot>/thumbs/<id>.enc`。
- Authority：`cn.vectory.syncplayer.media`；权限：`cn.vectory.syncplayer.permission.READ_MEDIA`（signature）。
- schema 破坏性迁移（spec §8 / D3）：v17→v18 清空媒体表；个人侧载单用户，可接受。
- 包路径：加密核心放 `cn.vectory.syncplayer.crypto`；卷抽象放 `cn.vectory.syncplayer.storage.volume`；Provider 放 `cn.vectory.syncplayer.provider`。
- 每步记录 diff（`git rev-parse HEAD` 基线 + `git diff --stat`），**ocmar 默认不提交**，除非用户明确要求。
- 既有 `MasterKey`（DatabaseModule，用于 EncryptedSharedPreferences）**不得**复用为媒体密钥；媒体密钥独立。

---

### Task 1: CryptoScheme — AES-CTR 核心与 HKDF/GCM

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/crypto/CryptoScheme.kt`
- Test: `app/src/test/java/cn/vectory/syncplayer/crypto/CryptoSchemeTest.kt`

**Interfaces:**
- Consumes: 无（纯 JVM）。
- Produces:
  - `CryptoScheme.deriveContentKey(master: SecretKey): SecretKey`
  - `CryptoScheme.deriveThumbKey(master: SecretKey): SecretKey`
  - `CryptoScheme.newIv(): ByteArray`（8 字节随机）
  - `CryptoScheme.encryptStream(key, iv, sink): OutputStream`（CTR 加密写流，顺序用）
  - `CryptoScheme.decryptBlockAlignedRange(key, iv, startBlockIndex, ciphertext, off, len): ByteArray`（FD 回调用，从指定 block 起顺序解密）
  - `CryptoScheme.encryptThumb(thumbKey, plain): ByteArray` / `decryptThumb(thumbKey, encoded): ByteArray`（随机 IV 包含在返回数据中）

**Acceptance Criteria:**
- `T1-C1`: `encryptStream` 写入的密文经 `decryptBlockAlignedRange` 从 block 0 解密，字节与原文逐字节相等（test: `ctrRoundTripFull`）。
- `T1-C2`: 对任意非对齐 offset，从该 offset 解密出的字节等于原文对应切片（test: `ctrSeekArbitraryOffset` 覆盖 offset=0/1/15/16/17/1000/跨多 block）。
- `T1-C3`: 连续两次 `newIv()` 不相等；1000 次无碰撞（test: `ivUniqueness`）。
- `T1-C4`: `encryptThumb`/`decryptThumb` 往返字节相等，且篡改 1 字节密文后 `decryptThumb` 抛异常（test: `gcmThumbRoundTrip` + `gcmThumbTamper`）。
- `T1-C5`: `decryptBlockAlignedRange` 在 startBlockIndex=100 与从 0 解密后跳到 offset=100*16 的结果一致（test: `seekMatchesSequentialSkip`）。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/cn/vectory/syncplayer/crypto/CryptoSchemeTest.kt
package cn.vectory.syncplayer.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class CryptoSchemeTest {
    private val master: SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val contentKey = CryptoScheme.deriveContentKey(master)
    private val thumbKey = CryptoScheme.deriveThumbKey(master)

    private fun dec(key: SecretKey, iv: ByteArray, cipher: ByteArray, offset: Long): ByteArray {
        val startBlock = offset / 16
        val skip = (offset - startBlock * 16).toInt()
        val aligned = startBlock.toInt() * 16
        val plain = CryptoScheme.decryptBlockAlignedRange(key, iv, startBlock, cipher, aligned, cipher.size - aligned)
        return plain.copyOfRange(skip, plain.size)
    }

    @Test fun ctrRoundTripFull() {
        val iv = CryptoScheme.newIv(); val plain = "hello world 0123456789".toByteArray()
        val cipher = ByteArrayOutputStream().apply { CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) } }.toByteArray()
        assertArrayEquals(plain, dec(contentKey, iv, cipher, 0))
    }

    @Test fun ctrSeekArbitraryOffset() {
        val iv = CryptoScheme.newIv(); val plain = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val cipher = ByteArrayOutputStream().apply { CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) } }.toByteArray()
        for (off in longArrayOf(0, 1, 15, 16, 17, 1000, 4095)) {
            val want = plain.copyOfRange(off.toInt(), off.toInt() + 1)
            assertEquals("offset=$off", want[0], dec(contentKey, iv, cipher, off)[0])
        }
    }

    @Test fun ivUniqueness() {
        val set = (1..1000).map { CryptoScheme.newIv().toList() }.toSet()
        assertEquals(1000, set.size)
    }

    @Test fun gcmThumbRoundTrip() {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val encoded = CryptoScheme.encryptThumb(thumbKey, plain)
        assertArrayEquals(plain, CryptoScheme.decryptThumb(thumbKey, encoded))
    }

    @Test fun gcmThumbTamper() {
        val plain = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val encoded = CryptoScheme.encryptThumb(thumbKey, plain).copyOf().apply { this[20] = (this[20].toInt() xor 0xFF).toByte() }
        assertThrows(Exception::class.java) { CryptoScheme.decryptThumb(thumbKey, encoded) }
    }

    @Test fun seekMatchesSequentialSkip() {
        val iv = CryptoScheme.newIv(); val plain = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val cipher = ByteArrayOutputStream().apply { CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) } }.toByteArray()
        val fromBlock100 = CryptoScheme.decryptBlockAlignedRange(contentKey, iv, 100L, cipher, 100 * 16, cipher.size - 100 * 16)
        assertArrayEquals(plain.copyOfRange(100 * 16, 101 * 16), fromBlock100.copyOfRange(0, 16))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.crypto.CryptoSchemeTest"`
Expected: FAIL（`CryptoScheme` 未定义 / Unresolved reference）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/crypto/CryptoScheme.kt
package cn.vectory.syncplayer.crypto

import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CryptoScheme {
    private const val IV_LEN = 8             // 64-bit nonce
    private const val BLOCK = 16
    private const val GCM_TAG = 16
    private const val TRANSFORM = "AES/CTR/NoPadding"
    private const val HKDF_INFO_CONTENT = "syncplayer/content".toByteArray()
    private const val HKDF_INFO_THUMB = "syncplayer/thumb".toByteArray()

    fun deriveContentKey(master: SecretKey): SecretKey = hkdf(master, HKDF_INFO_CONTENT)
    fun deriveThumbKey(master: SecretKey): SecretKey = hkdf(master, HKDF_INFO_THUMB)

    private fun hkdf(master: SecretKey, info: ByteArray): SecretKey {
        // HKDF-Extract: IKM = raw AES key bytes, salt = zero(32)
        val ikm = master.encoded
        val salt = ByteArray(32)
        val prkMac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prkBytes = prkMac.doFinal(ikm)
        // Expand single block: T(1) = HMAC(prk, info || 0x01), L=32
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prkBytes, "HmacSHA256")) }
        val okm = expand.doFinal(info + byteArrayOf(0x01))
        return SecretKeySpec(okm, "AES")
    }

    fun newIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

    /** CTR 加密写流：写入明文 → 内部产出密文写入 [sink]。顺序写，用于下载即加密。 */
    fun encryptStream(key: SecretKey, iv: ByteArray, sink: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, key, counterBlock(iv, 0L))
        }
        return CipherOutputStream(sink, cipher)
    }

    /**
     * 从 [startBlockIndex] 起顺序解密 [ciphertext] 的 [off]/[len] 区间，返回从 startBlock*16 计的明文
     * （长度 = len；区间外的块仍被解密为无意义字节，调用方按需切片）。
     * 每次 onRead 调用新建 Cipher → 无状态、线程安全。
     */
    fun decryptBlockAlignedRange(
        key: SecretKey, iv: ByteArray, startBlockIndex: Long,
        ciphertext: ByteArray, off: Int, len: Int
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, counterBlock(iv, startBlockIndex))
        }
        return cipher.doFinal(ciphertext, off, len)
    }

    /** 构造 16 字节计数块：IV(8) ‖ counter(8 大端)。 */
    private fun counterBlock(iv: ByteArray, counter: Long): IvParameterSpec {
        require(iv.size == IV_LEN)
        val blk = ByteBuffer.allocate(BLOCK).order(ByteOrder.BIG_ENDIAN)
            .put(iv)
            .putLong(counter)
            .array()
        return IvParameterSpec(blk)
    }

    fun encryptThumb(thumbKey: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG * 8, iv))
        }
        return byteArrayOf(1) + iv + cipher.doFinal(plain)
    }

    fun decryptThumb(thumbKey: SecretKey, encoded: ByteArray): ByteArray {
        require(encoded.size >= 29 && encoded[0].toInt() == 1)
        val iv = encoded.copyOfRange(1, 13)
        val cipherBytes = encoded.copyOfRange(13, encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, thumbKey, GCMParameterSpec(GCM_TAG * 8, iv))
        }
        return cipher.doFinal(cipherBytes)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.crypto.CryptoSchemeTest"`
Expected: PASS（6 个测试全过）。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD                      # baseline
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin   # 编译验证
git diff --stat $(git rev-parse HEAD)   # 记录本任务改动
```

---

### Task 2: MediaCryptoMaster — Keystore KEK 包裹软件 AES 主密钥

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/crypto/MediaCryptoMaster.kt`
- Modify: `app/src/main/java/cn/vectory/syncplayer/di/SyncModule.kt`
- Test: `app/src/test/java/cn/vectory/syncplayer/crypto/MediaCryptoMasterTest.kt`（软件部分），instrumented 部分见验收说明。

**Interfaces:**
- Consumes: `EncryptedSharedPreferences`（DatabaseModule 已提供，作为包裹密钥的存储）。
- Produces: `MediaCryptoMaster.masterKey(): SecretKey`（软件 AES-256，启动时解包入内存；HKDF 派生见 Task 1）。

**设计要点：** Keystore 密钥作为 KEK（仅 AES/GCM 包裹用途），真正的 content master 是软件 AES 密钥——避免 Keystore 逐块加密的性能损耗。包裹 blob 存于 EncryptedSharedPreferences key=`media_master_wrapped`。

**Acceptance Criteria:**
- `T2-C1`: 首次 `masterKey()` 生成并持久化；同进程再次调用返回**等价**密钥（`encoded` 相等）（test: `keyStableWithinProcess`，用可注入的 KEK 抽象绕过真实 Keystore）。
- `T2-C2`: 模拟"已有 wrapped blob"时，`masterKey()` 解包且不重新生成（test: `unwrapsExisting`）。
- `T2-C3`: `compileDebugKotlin` 通过（真实 Keystore 因需要设备，仅在 instrumented/真机验证；单元测用 fake KEK）。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/cn/vectory/syncplayer/crypto/MediaCryptoMasterTest.kt
package cn.vectory.syncplayer.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MediaCryptoMasterTest {
    /** 纯软件 KEK 替身，验证 wrap/unwrap 逻辑（绕过 AndroidKeyStore）。 */
    private class FakeKek(val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) {
        fun wrap(mk: SecretKey): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(mk.encoded)
        }
        fun unwrap(blob: ByteArray): SecretKey {
            val iv = blob.copyOfRange(0, 12); val ct = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return javax.crypto.spec.SecretKeySpec(c.doFinal(ct), "AES")
        }
    }

    private class FakeStore(var blob: ByteArray? = null) {
        fun get(): ByteArray? = blob
        fun set(b: ByteArray) { blob = b }
    }

    @Test fun keyStableWithinProcess() {
        val kek = FakeKek(); val store = FakeStore()
        val master = MediaCryptoMaster({ kek }, { store.get() }, { store.set(it) }).masterKey()
        val again = MediaCryptoMaster({ kek }, { store.get() }, { store.set(it) }).masterKey()
        assertArrayEquals(master.encoded, again.encoded)
        assertNotNull(store.blob)          // 已持久化
    }

    @Test fun unwrapsExisting() {
        val kek = FakeKek()
        val preWrapped = kek.wrap(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val store = FakeStore(preWrapped)
        val master = MediaCryptoMaster({ kek }, { store.get() }, { store.set(it) }).masterKey()
        assertEquals(32, master.encoded.size)
        assertSame(preWrapped, store.blob) // 未覆盖
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.crypto.MediaCryptoMasterTest"`
Expected: FAIL（`MediaCryptoMaster` 未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/crypto/MediaCryptoMaster.kt
package cn.vectory.syncplayer.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** 抽象 KEK，便于单元测试用软件替身。 */
interface KekProvider { fun wrap(mk: SecretKey): ByteArray; fun unwrap(blob: ByteArray): SecretKey }

@Singleton
class MediaCryptoMaster @Inject constructor(
    private val kekProvider: KekProvider,
    private val loadBlob: () -> ByteArray?,
    private val storeBlob: (ByteArray) -> Unit,
) {
    @Volatile private var cached: SecretKey? = null

    fun masterKey(): SecretKey = cached ?: synchronized(this) {
        cached ?: (loadBlob()?.let { kekProvider.unwrap(it) } ?: generateAndStore()).also { cached = it }
    }

    private fun generateAndStore(): SecretKey {
        val mk = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        storeBlob(kekProvider.wrap(mk))
        return mk
    }

    companion object { const val PREF_KEY = "media_master_wrapped" }
}

/** 真实 Keystore KEK 实现（Hilt 提供时用）。 */
@Singleton
class KeystoreKekProvider @Inject constructor(@androidx.hilt.work.HiltWorker private val context: Context?) : KekProvider {
    private val alias = "syncplayer_media_kek"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
        }
        return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }
    override fun wrap(mk: SecretKey): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(mk.encoded)
    }
    override fun unwrap(blob: ByteArray): SecretKey {
        val iv = blob.copyOfRange(0, 12); val ct = blob.copyOfRange(12, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return SecretKeySpec(c.doFinal(ct), "AES")
    }
}
```

Hilt 绑定（`SyncModule.kt` 追加）：

```kotlin
package cn.vectory.syncplayer.di

import cn.vectory.syncplayer.crypto.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.security.crypto.EncryptedSharedPreferences
import android.content.Context
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides @Singleton
    fun provideMediaCryptoMaster(
        @ApplicationContext ctx: Context,
        prefs: EncryptedSharedPreferences,
    ): MediaCryptoMaster {
        val kek = KeystoreKekProvider(ctx)
        return MediaCryptoMaster(
            kekProvider = kek,
            loadBlob = { prefs.getString(MediaCryptoMaster.PREF_KEY, null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } },
            storeBlob = { prefs.edit().putString(MediaCryptoMaster.PREF_KEY, android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).apply() },
        )
    }
}
```

> 注：`KeystoreKekProvider` 构造里的 `@androidx.hilt.work.HiltWorker` 限定符误用——实现时改为 `@ApplicationContext ctx: Context`（删掉 HiltWorker 注解），`provideMediaCryptoMaster` 直接 `new`。修正签名：`class KeystoreKekProvider(@ApplicationContext private val context: Context) : KekProvider`，去掉 `@Inject`（由 SyncModule 手动 new）。该修正在 Step 3 落实时直接采用正确签名。

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.crypto.MediaCryptoMasterTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 3: MediaVolume 抽象 + FileVolume（含不透明路径派生）

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolume.kt`
- Create: `app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt`
- Create: `app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaPaths.kt`（不透明路径派生）
- Test: `app/src/test/java/cn/vectory/syncplayer/storage/volume/FileVolumeTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `MediaVolume`（接口，见下）
  - `SeekableReadHandle`（`fun seek(pos); fun read(buf,off,len): Int; fun size(): Long; override fun close()`）
  - `MediaPaths.mediaRelPath(id): String` = `"${id/1000}/${id}.enc"`；`MediaPaths.thumbRelPath(id): String` = `"thumbs/${id}.enc"`；`tmpSuffix = ".enc.tmp"`
  - `FileVolume(root: File): MediaVolume`

```kotlin
interface MediaVolume {
    val volumeId: String
    suspend fun openWrite(relPath: String): OutputStream
    suspend fun openRead(relPath: String): SeekableReadHandle
    suspend fun list(dirRelPath: String): List<String>
    suspend fun delete(relPath: String): Boolean
    suspend fun exists(relPath: String): Boolean
    suspend fun mkdirs(dirRelPath: String)
    suspend fun rename(from: String, to: String): Boolean
    fun usableSpace(): Long
}
```

**Acceptance Criteria:**
- `T3-C1`: 经 `openWrite` 写入 N 字节后 `openRead` 读回相等；`seek(mid)` 后读取等于后半段（test: `writeReadSeek`）。
- `T3-C2`: `rename("a","b")` 后 `exists("a")=false` 且 `exists("b")=true`（test: `renameAtomicity`）。
- `T3-C3`: `MediaPaths.mediaRelPath(42)` = `"0/42.enc"`；`mediaRelPath(2500)` = `"2/2500.enc"`；`thumbRelPath(7)` = `"thumbs/7.enc"`（test: `pathDerivation`）。
- `T3-C4`: `delete` 后 `exists=false`；`usableSpace()>0`（test: `deleteAndSpace`）。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/cn/vectory/syncplayer/storage/volume/FileVolumeTest.kt
package cn.vectory.syncplayer.storage.volume

import kotlinx.coroutines.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FileVolumeTest {
    private fun newVol(): FileVolume = FileVolume(File(createTempDir(), "vol").apply { mkdirs() })

    @Test fun pathDerivation() {
        assertEquals("0/42.enc", MediaPaths.mediaRelPath(42))
        assertEquals("2/2500.enc", MediaPaths.mediaRelPath(2500))
        assertEquals("thumbs/7.enc", MediaPaths.thumbRelPath(7))
    }

    @Test fun writeReadSeek() = runTest {
        val v = newVol(); val payload = ByteArray(500) { (it and 0xFF).toByte() }
        v.openWrite("0/1.enc.tmp").use { it.write(payload) }
        v.rename("0/1.enc.tmp", "0/1.enc")
        v.openRead("0/1.enc").use { h ->
            assertEquals(500, h.size())
            val buf = ByteArray(500); assertEquals(500, h.read(buf, 0, 500))
            assertArrayEquals(payload, buf)
            h.seek(250); val half = ByteArray(250); assertEquals(250, h.read(half, 0, 250))
            assertArrayEquals(payload.copyOfRange(250, 500), half)
        }
    }

    @Test fun renameAtomicity() = runTest {
        val v = newVol(); v.openWrite("a").use { it.write(byteArrayOf(1)) }
        assertTrue(v.rename("a", "b"))
        assertFalse(v.exists("a")); assertTrue(v.exists("b"))
    }

    @Test fun deleteAndSpace() = runTest {
        val v = newVol(); v.openWrite("x").use { it.write(byteArrayOf(1,2,3)) }
        assertTrue(v.exists("x")); assertTrue(v.delete("x")); assertFalse(v.exists("x"))
        assertTrue(v.usableSpace() > 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.storage.volume.FileVolumeTest"`
Expected: FAIL（类型未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaPaths.kt
package cn.vectory.syncplayer.storage.volume

object MediaPaths {
    const val TMP_SUFFIX = ".enc.tmp"
    fun mediaRelPath(id: Long) = "${id / 1000}/${id}.enc"
    fun thumbRelPath(id: Long) = "thumbs/${id}.enc"
}
```

```kotlin
// app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolume.kt
package cn.vectory.syncplayer.storage.volume

import java.io.Closeable
import java.io.OutputStream

interface SeekableReadHandle : Closeable {
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun size(): Long
}

interface MediaVolume {
    val volumeId: String
    suspend fun openWrite(relPath: String): OutputStream
    suspend fun openRead(relPath: String): SeekableReadHandle
    suspend fun list(dirRelPath: String): List<String>
    suspend fun delete(relPath: String): Boolean
    suspend fun exists(relPath: String): Boolean
    suspend fun mkdirs(dirRelPath: String)
    suspend fun rename(from: String, to: String): Boolean
    fun usableSpace(): Long
}
```

```kotlin
// app/src/main/java/cn/vectory/syncplayer/storage/volume/FileVolume.kt
package cn.vectory.syncplayer.storage.volume

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

class FileVolume(val root: File) : MediaVolume {
    override val volumeId: String = "internal"
    private fun resolve(relPath: String): File {
        require(!relPath.startsWith('/') && relPath.split('/').none { it == ".." })
        val file = File(root, relPath).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "path escapes volume" }
        return file
    }

    override suspend fun openWrite(relPath: String): OutputStream {
        val f = resolve(relPath); f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }
    override suspend fun openRead(relPath: String): SeekableReadHandle {
        val raf = RandomAccessFile(resolve(relPath), "r")
        return object : SeekableReadHandle {
            override fun seek(position: Long) { raf.seek(position) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
            override fun size(): Long = raf.length()
            override fun close() { raf.close() }
        }
    }
    override suspend fun list(dirRelPath: String): List<String> = resolve(dirRelPath).list()?.toList() ?: emptyList()
    override suspend fun delete(relPath: String): Boolean = resolve(relPath).delete()
    override suspend fun exists(relPath: String): Boolean = resolve(relPath).exists()
    override suspend fun mkdirs(dirRelPath: String) { resolve(dirRelPath).mkdirs() }
    override suspend fun rename(from: String, to: String): Boolean {
        val src = resolve(from); val dst = resolve(to); dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }
    override fun usableSpace(): Long = root.usableSpace
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.storage.volume.FileVolumeTest"`
Expected: PASS（4 个测试）。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 4: SafVolume — SAF DocumentFile 后端

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt`
- Test: `app/src/androidTest/java/cn/vectory/syncplayer/storage/volume/SafVolumeTest.kt`（instrumented，需设备）

**Interfaces:**
- Consumes: `context: Context`, `treeUri: Uri`（持久化的 SAF tree URI）。
- Produces: `SafVolume : MediaVolume`（`volumeId` = 规范化 treeUri 字符串；v1 只用于校验当前活跃卷，不作为跨卷 Registry key）。

**设计要点：** `openRead` 用 `contentResolver.openFileDescriptor(uri,"r")` 得可 seek 的 `ParcelFileDescriptor`（本地卷支持）。`usableSpace` 用 `StorageManager` 匹配卷 + `StatFs`，取不到返回 `Long.MAX_VALUE`（降级，spec R8）。

**Acceptance Criteria:**
- `T4-C1`: instrumented：在内部存储子树建 `SafVolume`，`openWrite→rename→openRead` 读回相等，`seek` 生效。
- `T4-C2`: instrumented：`delete` 后 `exists=false`；`volumeId` 在同 treeUri 下稳定。
- `T4-C3`: `compileDebugKotlin` + `assembleDebugAndroidTest` 通过。

- [ ] **Step 1: Write the failing instrumented test（骨架）**

```kotlin
// app/src/androidTest/java/cn/vectory/syncplayer/storage/volume/SafVolumeTest.kt
package cn.vectory.syncplayer.storage.volume

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafVolumeTest {
    // 测试必须使用真实、已持久化授权的 treeUri（由测试 fixture/设备参数提供）。
    // 不允许用 assertTrue(true) 伪造 SAF seek 能力；无法提供可 seek 的 Provider 时测试必须失败。
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun requirePersistedTreeUri(): Uri = Uri.parse(
        checkNotNull(InstrumentationRegistry.getArguments().getString("treeUri")) {
            "Pass -e treeUri <persisted ACTION_OPEN_DOCUMENT_TREE URI> to connectedAndroidTest"
        }
    )

    @Test fun writeReadRoundTrip_onInternalSubtree() {
        val treeUri = requirePersistedTreeUri()
        val volume = SafVolume(ctx, treeUri)
        runBlocking {
            val payload = ByteArray(257) { it.toByte() }
            volume.openWrite("probe/1.enc").use { it.write(payload) }
            volume.openRead("probe/1.enc").use { handle ->
                assertEquals(payload.size.toLong(), handle.size())
                handle.seek(129)
                val tail = ByteArray(payload.size - 129)
                assertEquals(tail.size, handle.read(tail, 0, tail.size))
                assertArrayEquals(payload.copyOfRange(129, payload.size), tail)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails / compiles**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:assembleDebugAndroidTest`
Expected: 编译失败（`SafVolume` 未定义）。实现后应编译通过。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/storage/volume/SafVolume.kt
package cn.vectory.syncplayer.storage.volume

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

class SafVolume(
    private val context: Context,
    val treeUri: Uri,
) : MediaVolume {
    override val volumeId: String = "saf-" + treeUri.normalizeScheme().toString()

    private fun fromTree(): DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("invalid tree uri")
    private fun navigate(relPath: String, create: Boolean = false): DocumentFile? {
        var node: DocumentFile = fromTree()
        for (seg in relPath.split('/').filter { it.isNotEmpty() }) {
            node = node.findFile(seg) ?: if (create) node.createDirectory(seg) else return null
        }
        return node
    }
    private fun fileUri(relPath: String): Uri {
        val (dir, name) = relPath.substringBeforeLast('/') to relPath.substringAfterLast('/')
        val parent = if (dir.isBlank()) fromTree() else (navigate(dir, false) ?: error("no dir"))
        return parent.findFile(name)?.uri ?: error("file not found: $relPath")
    }

    override suspend fun openWrite(relPath: String): OutputStream {
        val (dir, name) = relPath.substringBeforeLast('/') to relPath.substringAfterLast('/')
        val parent = if (dir.isBlank()) fromTree() else (navigate(dir, true) ?: error("no dir"))
        parent.findFile(name)?.delete()
        val df = parent.createFile("application/octet-stream", name)!!
        return context.contentResolver.openOutputStream(df.uri, "w")
            ?: error("cannot open output")
    }
    override suspend fun openRead(relPath: String): SeekableReadHandle {
        val pfd = context.contentResolver.openFileDescriptor(fileUri(relPath), "r")
            ?: error("cannot open pfd")
        val fd = pfd.fileDescriptor
        return object : SeekableReadHandle {
            override fun seek(position: Long) { android.system.Os.lseek(pfd.fileDescriptor, position, android.system.OsConstants.SEEK_SET) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                android.system.Os.read(pfd.fileDescriptor, buffer, offset, length)
            override fun size(): Long = pfd.statSize
            override fun close() { pfd.close() }
        }
    }
    override suspend fun list(dirRelPath: String): List<String> =
        (navigate(dirRelPath) ?: fromTree()).listFiles().map { it.name ?: "" }
    override suspend fun delete(relPath: String): Boolean =
        (navigate(relPath)?.delete() ?: false)
    override suspend fun exists(relPath: String): Boolean = navigate(relPath) != null
    override suspend fun mkdirs(dirRelPath: String) { navigate(dirRelPath, true) }
    override suspend fun rename(from: String, to: String): Boolean {
        val node = navigate(from) ?: return false
        val targetName = to.substringAfterLast('/')
        if (!node.renameTo(targetName)) return false
        val parent = navigate(to.substringBeforeLast('/', "")) ?: fromTree()
        return parent.findFile(targetName) != null && navigate(from) == null
    }
    override fun usableSpace(): Long = try {
        // 匹配卷根后用 StatFs；取不到则降级
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val vol = sm.storageVolumes.firstOrNull { !it.isPrimary } ?: return Long.MAX_VALUE
        val dir = vol.directory ?: return Long.MAX_VALUE
        StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }
}
```

> 注：`openRead` 用 `Os.lseek/Os.read` 直接操作 `pfd.fileDescriptor`（API 21+，minSdk 34 OK）；接入卷前必须实际探测 seek，不能用伪测试替代。

- [ ] **Step 4: Run test to verify it passes（编译维度）**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:assembleDebugAndroidTest`
Expected: 编译通过；真机 round-trip 见 Task 15 手测清单。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
git diff --stat $(git rev-parse HEAD)
```

---

### Task 5: EncryptedMediaStore — 编排加密 + 卷

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt`
- Test: `app/src/test/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStoreTest.kt`

**Interfaces:**
- Consumes: `MediaCryptoMaster`、`CryptoScheme`、`MediaVolume`、`MediaPaths`。
- Produces:
  - `EncryptedMediaStore(volume: MediaVolume, masterProvider: () -> SecretKey)`
  - `suspend fun openEncryptSink(id, iv): OutputStream`（明文写入 → CTR 加密 → 卷 `.enc.tmp`）
  - `suspend fun finalize(id): Boolean`（rename `.enc.tmp→.enc`）
  - `suspend fun cancel(id)`（删 `.enc.tmp`）
  - `suspend fun openCiphertextRead(id): SeekableReadHandle`（原始密文，供 FD 回调用）
  - `suspend fun delete(id): Boolean` / `suspend fun exists(id): Boolean` / `fun usableSpace(): Long`
  - `suspend fun writeThumb(id, plain: ByteArray)` / `suspend fun readThumb(id): ByteArray?` / `suspend fun deleteThumb(id)`
  - `val volumeId: String`，`fun contentKey()/thumbKey()`

**Acceptance Criteria:**
- `T5-C1`: `openEncryptSink` 写明文 → `finalize` → `openCiphertextRead` 读出的**密文**经 `CryptoScheme` 解密后与原文相等（test: `encryptThenReadBack`）。
- `T5-C2`: `writeThumb`/`readThumb` 往返相等；`deleteThumb` 后 `readThumb=null`（test: `thumbRoundTrip`）。
- `T5-C3`: `cancel(id)` 后 `exists(id)=false` 且 `.enc.tmp` 残留被清（test: `cancelCleansTmp`）。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStoreTest.kt
package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import kotlinx.coroutines.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

class EncryptedMediaStoreTest {
    private val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private fun newStore(): EncryptedMediaStore =
        EncryptedMediaStore(FileVolume(File(createTempDir(), "v").apply { mkdirs() })) { master }

    @Test fun encryptThenReadBack() = runTest {
        val store = newStore(); val id = 10L
        val iv = CryptoScheme.newIv()
        val plain = ByteArray(2048) { (it and 0xFF).toByte() }
        store.openEncryptSink(id, iv).use { it.write(plain) }
        assertTrue(store.finalize(id))
        val key = CryptoScheme.deriveContentKey(master)
        val cipher = ByteArrayOutputStream().apply {
            store.openCiphertextRead(id).use { h ->
                val b = ByteArray(2048); val n = h.read(b, 0, 2048); write(b, 0, n)
            }
        }.toByteArray()
        val dec = CryptoScheme.decryptBlockAlignedRange(key, iv, 0L, cipher, 0, cipher.size)
        assertArrayEquals(plain, dec)
    }

    @Test fun thumbRoundTrip() = runTest {
        val store = newStore(); val plain = ByteArray(300) { it.toByte() }
        store.writeThumb(5L, plain)
        assertArrayEquals(plain, store.readThumb(5L))
        store.deleteThumb(5L); assertNull(store.readThumb(5L))
    }

    @Test fun cancelCleansTmp() = runTest {
        val store = newStore()
        store.openEncryptSink(8L, CryptoScheme.newIv()).use { it.write(byteArrayOf(1,2,3)) }
        store.cancel(8L)
        assertFalse(store.exists(8L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.storage.volume.EncryptedMediaStoreTest"`
Expected: FAIL（类型未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/storage/volume/EncryptedMediaStore.kt
package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import javax.crypto.SecretKey

class EncryptedMediaStore(
    val volume: MediaVolume,
    private val masterProvider: () -> SecretKey,
) {
    val volumeId: String get() = volume.volumeId
    private val masterKey: SecretKey get() = masterProvider()
    fun contentKey() = CryptoScheme.deriveContentKey(masterKey)
    private fun thumbKey() = CryptoScheme.deriveThumbKey(masterKey)

    private fun tmpMedia(id: Long) = MediaPaths.mediaRelPath(id) + MediaPaths.TMP_SUFFIX
    private fun finalMedia(id: Long) = MediaPaths.mediaRelPath(id)

    suspend fun openEncryptSink(id: Long, iv: ByteArray): java.io.OutputStream {
        val sink = volume.openWrite(tmpMedia(id))
        return CryptoScheme.encryptStream(contentKey(), iv, sink)
    }
    suspend fun finalize(id: Long): Boolean = volume.rename(tmpMedia(id), finalMedia(id))
    suspend fun cancel(id: Long) { volume.delete(tmpMedia(id)) }
    suspend fun openCiphertextRead(id: Long): SeekableReadHandle = volume.openRead(finalMedia(id))
    suspend fun delete(id: Long): Boolean = volume.delete(finalMedia(id))
    suspend fun exists(id: Long): Boolean = volume.exists(finalMedia(id))
    fun usableSpace(): Long = volume.usableSpace()

    suspend fun writeThumb(id: Long, plain: ByteArray) {
        val encoded = CryptoScheme.encryptThumb(thumbKey(), plain)
        volume.openWrite(MediaPaths.thumbRelPath(id)).use { it.write(encoded) }
    }
    suspend fun readThumb(id: Long): ByteArray? {
        if (!volume.exists(MediaPaths.thumbRelPath(id))) return null
        val buf = java.io.ByteArrayOutputStream()
        // 缩略图小文件，全量读；先检查长度再分配
        buf.use { b -> volume.openRead(MediaPaths.thumbRelPath(id)).use { h ->
            require(h.size() in 29..1_048_576) { "invalid thumbnail size" }
            val tmp = ByteArray(h.size().toInt()); h.read(tmp, 0, tmp.size); b.write(tmp)
        } }
        val encoded = buf.toByteArray()
        require(encoded.size <= 1_048_576) { "thumbnail too large" }
        return CryptoScheme.decryptThumb(thumbKey(), encoded)
    }
    suspend fun deleteThumb(id: Long): Boolean = volume.delete(MediaPaths.thumbRelPath(id))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.storage.volume.EncryptedMediaStoreTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 6: DecryptingFdCallback — 可 seek 代理 FD 解密器

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/crypto/DecryptingFdCallback.kt`
- Create: `app/src/main/java/cn/vectory/syncplayer/crypto/RangeDecryptor.kt`（纯逻辑，便于单测）
- Test: `app/src/test/java/cn/vectory/syncplayer/crypto/RangeDecryptorTest.kt`

**设计要点：** 把"按 offset 解密"拆成纯逻辑 `RangeDecryptor`（可纯 JVM 单测）+ Android `DecryptingFdCallback`（在其 onRead 里调用 RangeDecryptor + 读密文句柄）。cipher 用 `ThreadLocal`。

**Interfaces:**
- Consumes: `SeekableReadHandle`（密文）、`SecretKey contentKey`、`iv`、`plaintextSize`、`CryptoScheme`。
- Produces: `RangeDecryptor.readRange(handle, offset, len): ByteArray`；`DecryptingFdCallback : ProxyFileDescriptorCallback`。

**Acceptance Criteria:**
- `T6-C1`: 对任意 `(offset,len)`（含非对齐、跨 block、接近末尾），`RangeDecryptor.readRange` 返回的字节等于明文切片（test: `readRangeMatchesPlaintextSlice`，覆盖 offset=0/13/32/跨 3 block/末尾不足一块）。
- `T6-C2`: `readRange` 在 `offset+len > size` 时返回截断结果（长度 = size-offset）（test: `readRangeClampsAtEof`）。
- `T6-C3`: `compileDebugKotlin` 通过（`DecryptingFdCallback` 本体因需 `ProxyFileDescriptorCallback`，由 instrumented/真机验证，逻辑层由 T6-C1/C2 覆盖）。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/cn/vectory/syncplayer/crypto/RangeDecryptorTest.kt
package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class RangeDecryptorTest {
    private val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val contentKey = CryptoScheme.deriveContentKey(master)
    private val iv = CryptoScheme.newIv()

    private fun fixture(plain: ByteArray): Pair<ByteArray, SeekableReadHandle> {
        // 生成密文
        val cipher = java.io.ByteArrayOutputStream().apply {
            CryptoScheme.encryptStream(contentKey, iv, this).use { it.write(plain) }
        }.toByteArray()
        // 内存 SeekableReadHandle
        val handle = object : SeekableReadHandle {
            private val buf = cipher; private var pos = 0L
            override fun seek(position: Long) { pos = position }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = minOf(length.toLong(), (buf.size - pos).coerceAtLeast(0)).toInt()
                if (n <= 0) return -1
                System.arraycopy(buf, pos.toInt(), buffer, offset, n); pos += n; return n
            }
            override fun size(): Long = buf.size.toLong()
            override fun close() {}
        }
        return plain to handle
    }

    @Test fun readRangeMatchesPlaintextSlice() {
        val (plain, handle) = fixture(ByteArray(2000) { (it and 0xFF).toByte() })
        val rd = RangeDecryptor(contentKey, iv, plain.size.toLong())
        for ((off, len) in listOf(0 to 1, 13 to 17, 32 to 64, 0 to 2000, 1500 to 500, 1999 to 1)) {
            val got = rd.readRange(handle, off.toLong(), len)
            assertArrayEquals("off=$off len=$len", plain.copyOfRange(off, off + len), got)
        }
    }

    @Test fun readRangeClampsAtEof() {
        val (plain, handle) = fixture(ByteArray(100) { it.toByte() })
        val rd = RangeDecryptor(contentKey, iv, 100L)
        val got = rd.readRange(handle, 90L, 50)
        assertEquals(10, got.size)
        assertArrayEquals(plain.copyOfRange(90, 100), got)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.crypto.RangeDecryptorTest"`
Expected: FAIL（`RangeDecryptor` 未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/crypto/RangeDecryptor.kt
package cn.vectory.syncplayer.crypto

import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import javax.crypto.SecretKey
import kotlin.math.min

class RangeDecryptor(
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) {
    private val cipherThreadLocal = ThreadLocal.withInitial {
        javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
    }

    fun readRange(handle: SeekableReadHandle, offset: Long, requestedLen: Int): ByteArray {
        require(offset >= 0 && requestedLen >= 0)
        if (offset >= size) return ByteArray(0)
        val effectiveLen = min(requestedLen.toLong(), size - offset).toInt()
        val startBlock = offset / 16
        val blockAlignedStart = startBlock * 16
        val skip = (offset - blockAlignedStart).toInt()
        // 读取覆盖 [blockAlignedStart, blockAlignedStart + skip + effectiveLen) 的密文（向上对齐 16）
        val cipherLen = ((skip + effectiveLen + 15) / 16) * 16
        val cipherBuf = ByteArray(cipherLen)
        handle.seek(blockAlignedStart)
        var read = 0
        while (read < cipherLen) {
            val n = handle.read(cipherBuf, read, cipherLen - read)
            if (n <= 0) break
            read += n
        }
        require(read >= skip + effectiveLen) { "truncated ciphertext" }
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key, counterBlock(startBlock))
        }
        val plain = cipher.doFinal(cipherBuf, 0, read)
        return plain.copyOfRange(skip, skip + effectiveLen)
    }

    private fun counterBlock(startBlock: Long): javax.crypto.spec.IvParameterSpec {
        val blk = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN)
            .put(iv).putLong(startBlock).array()
        return javax.crypto.spec.IvParameterSpec(blk)
    }
}
```

```kotlin
// app/src/main/java/cn/vectory/syncplayer/crypto/DecryptingFdCallback.kt
package cn.vectory.syncplayer.crypto

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import javax.crypto.SecretKey

class DecryptingFdCallback(
    private val handle: SeekableReadHandle,
    private val key: SecretKey,
    private val iv: ByteArray,
    private val size: Long,
) : ProxyFileDescriptorCallback() {
    private val decryptor = RangeDecryptor(key, iv, size)
    override fun onGetSize(): Long = size
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        val out = decryptor.readRange(handle, offset, size)
        System.arraycopy(out, 0, data, 0, out.size)
        return out.size
    }
    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
        throw ErrnoException("DecryptingFd", android.system.OsConstants.EBADF)
    override fun onRelease() { handle.close() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.crypto.RangeDecryptorTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 7: Room schema v17 → v18（破坏性，删绝对路径列）

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/data/local/entity/VideoItemEntity.kt`
- Modify: `app/src/main/java/cn/vectory/syncplayer/data/local/AppDatabase.kt`（version 17→18）
- Modify: `app/src/main/java/cn/vectory/syncplayer/di/DatabaseModule.kt`（注册 `MIGRATION_17_18`）
- Test: `app/src/androidTest/java/cn/vectory/syncplayer/data/local/Migration17To18Test.kt`（Room instrumented migration test）

**设计要点：** `localAbsolutePath`/`thumbnailPath` 删除，新增 `volumeId`/`iv`。`PlaybackRecordEntity` 有 FK(video_item, CASCADE) → 迁移必须连带重建 `playback_record`。CREATE TABLE 的 DDL **必须与 Room 生成的 v18 schema 完全一致**（hash 校验），DDL 从 `app/schemas/.../18.json` 复制。`fileSize` 即明文长=密文长，无需 plaintextSize 列。

**Interfaces:**
- Consumes: 无。
- Produces: 新 `VideoItemEntity`（字段 `id, syncRelationId, relativePath, parentPath, fileName, volumeId: String, iv: ByteArray, fileSize, duration, width, height, bitrate, frameRate, audioGainDb, dateSynced, remoteModifiedTime, star, mediaType, dateTaken`）。

**Acceptance Criteria:**
- `T7-C1`: `VideoItemEntity` 无 `localAbsolutePath`/`thumbnailPath`；含 `volumeId: String` 与 `iv: ByteArray`（编译 + 字段检查）。
- `T7-C2`: `Migration17To18Test`：用 v17 schema 建库并写入一行 video_item + 一行 playback_record，迁移到 v18 后，`video_item` 与 `playback_record` 均为空（破坏性清空），且 `AppDatabase` 可正常打开（Room hash 校验通过）。
- `T7-C3`: `app/schemas` 下生成 `18.json`；`compileDebugKotlin` 通过。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/androidTest/java/cn/vectory/syncplayer/data/local/Migration17To18Test.kt
package cn.vectory.syncplayer.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Migration17To18Test {
    private val dbName = "migration-test.db"
    @get:Rule val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = cn.vectory.syncplayer.data.local.AppDatabase::class.java,
    )

    @Test fun migrate17To18_wipesMediaTables() {
        val v17 = helper.createDatabase(dbName, 17)
        v17.execSQL("INSERT INTO video_item (syncRelationId,relativePath,parentPath,fileName,localAbsolutePath,fileSize,mediaType) VALUES (1,'a/b','a','b.mp4','/old/x',10,'VIDEO')")
        v17.execSQL("INSERT INTO playback_record (videoItemId,position,duration) VALUES (1,0,0)")
        v17.close()

        val migrated = helper.runMigrationsAndValidate(dbName, 18, true, DatabaseModule.MIGRATION_17_18)
        migrated.query("SELECT COUNT(*) FROM video_item").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM playback_record").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
        }
        migrated.close()
    }
}
```


- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:connectedDebugAndroidTest --tests "cn.vectory.syncplayer.data.local.Migration17To18Test"`
Expected: FAIL（v18 不存在 / MIGRATION_17_18 未定义）。

- [ ] **Step 3: Write minimal implementation**

更新实体：

```kotlin
// VideoItemEntity.kt（删除 localAbsolutePath/thumbnailPath；加 volumeId/iv）
@Entity(
    tableName = "video_item",
    indices = [
        Index(value = ["relativePath", "syncRelationId"], unique = true),
        Index(value = ["syncRelationId"]),
        Index(value = ["parentPath"]),
        Index(value = ["volumeId"])
    ]
)
data class VideoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncRelationId: Long = 0,
    val relativePath: String,
    val parentPath: String = "",
    val fileName: String,
    val volumeId: String,
    val iv: ByteArray,
    val fileSize: Long,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val audioGainDb: Float? = null,
    val dateSynced: Long = System.currentTimeMillis(),
    val remoteModifiedTime: Long? = null,
    val star: Int = 0,
    val mediaType: String = "VIDEO",
    val dateTaken: Long? = null
) {
    // 保持 data class 的字段语义；若未来手写比较，必须使用 iv.contentEquals。
}
```

`AppDatabase.kt`：`version = 17` → `version = 18`。

`DatabaseModule.kt`：在 `.addMigrations(...)` 链尾追加 `MIGRATION_17_18`，并新增：

```kotlin
// 破坏性清空：video_item 改列无法 ALTER，且 D3 接受数据丢失。
// 重建 video_item（新 schema）+ playback_record（FK 子表）。
// CREATE TABLE 的 DDL 必须与 app/schemas/.../18.json 完全一致。
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS playback_record")
        db.execSQL("DROP TABLE IF EXISTS video_item")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `video_item` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `syncRelationId` INTEGER NOT NULL,
                `relativePath` TEXT NOT NULL,
                `parentPath` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `volumeId` TEXT NOT NULL,
                `iv` BLOB NOT NULL,
                `fileSize` INTEGER NOT NULL,
                `duration` INTEGER,
                `width` INTEGER,
                `height` INTEGER,
                `bitrate` INTEGER,
                `frameRate` REAL,
                `audioGainDb` REAL,
                `dateSynced` INTEGER NOT NULL,
                `remoteModifiedTime` INTEGER,
                `star` INTEGER NOT NULL,
                `mediaType` TEXT NOT NULL,
                `dateTaken` INTEGER
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_video_item_relativePath_syncRelationId` ON `video_item` (`relativePath`, `syncRelationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_item_syncRelationId` ON `video_item` (`syncRelationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_item_parentPath` ON `video_item` (`parentPath`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_item_volumeId` ON `video_item` (`volumeId`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `playback_record` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `videoItemId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `duration` INTEGER NOT NULL,
                `lastPlayedTime` INTEGER NOT NULL,
                `isCompleted` INTEGER NOT NULL,
                `isTrashed` INTEGER NOT NULL,
                FOREIGN KEY(`videoItemId`) REFERENCES `video_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_record_videoItemId` ON `playback_record` (`videoItemId`)")
    }
}
```

> **关键：** 实现 Step 3 时，先改实体并跑一次 `./gradlew :app:kaptDebugKotlin`（或 assembleDebug）让 Room 生成 `18.json`，然后**逐字核对** `18.json` 里的 `createSql` 与上面 DDL 一致（列顺序/类型/索引名）。任何不一致都会在运行时触发 Room `IllegalStateException: Migration didn't properly handle`。`MIGRATION_17_18` 需改为 `internal`/`@VisibleForTesting` 以便测试引用。

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:connectedDebugAndroidTest --tests "cn.vectory.syncplayer.data.local.Migration17To18Test"`
Expected: PASS；`app/schemas` 下出现 `18.json`。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 8: RemoteFileClient.downloadTo + SMB/SFTP 实现

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/RemoteFileClient.kt`
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/smb/SmbFileClient.kt`（257-297）
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/sftp/SftpFileClient.kt`（158-195）
- Test: `app/src/test/java/cn/vectory/syncplayer/sync/RemoteFileClientContractTest.kt`（用 Fake 客户端验契约）

**设计要点：** 新增 `downloadTo(remotePath, sink: OutputStream, onProgress)`：**不**关闭 sink（调用方拥有生命周期，用于包裹加密流）。旧 `downloadFile(File)` 删除——SyncEngine 改走加密 sink（Task 9）。client 内部自算 `total` 并经 onProgress 上报。

**Acceptance Criteria:**
- `T8-C1`: `RemoteFileClient` 接口含 `suspend fun downloadTo(remotePath: String, sink: OutputStream, onProgress: ((Long,Long)->Unit)?): Boolean`，无旧 `downloadFile(File)`。
- `T8-C2`: Fake 客户端契约测试：`downloadTo` 把预设字节写入 sink，**不**关闭 sink，sink 关闭前数据可读（test: `downloadToWritesWithoutClosingSink`）。
- `T8-C3**: SMB/SFTP 实现编译通过（真实下载在 Task 9/15 集成验证）。

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/cn/vectory/syncplayer/sync/RemoteFileClientContractTest.kt
package cn.vectory.syncplayer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class RemoteFileClientContractTest {
    private class FakeClient(val payload: ByteArray) : RemoteFileClient {
        override suspend fun connect(source: cn.vectory.syncplayer.data.model.SyncSource, remotePath: String) {}
        override suspend fun disconnect() {}
        override suspend fun close() {}
        override suspend fun testConnection(source: cn.vectory.syncplayer.data.model.SyncSource, remotePath: String) = true
        override suspend fun listFiles(path: String) = emptyList<cn.vectory.syncplayer.data.model.RemoteFile>()
        override suspend fun listDirectories(path: String) = emptyList<cn.vectory.syncplayer.data.model.RemoteFile>()
        override suspend fun downloadTo(remotePath: String, sink: OutputStream, onProgress: ((Long, Long) -> Unit)?) = runBlocking { sink.write(payload); true }
        override suspend fun deleteFile(remotePath: String) = true
    }

    @Test fun downloadToWritesWithoutClosingSink() = runBlocking {
        class TrackingSink : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val sink = TrackingSink()
        val ok = FakeClient("HELLO".toByteArray()).downloadTo("x", sink, null)
        assertTrue(ok)
        assertFalse("client must NOT close the caller's sink", sink.closed)
        assertArrayEquals("HELLO".toByteArray(), sink.toByteArray())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.sync.RemoteFileClientContractTest"`
Expected: FAIL（`downloadTo` 未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// RemoteFileClient.kt
interface RemoteFileClient {
    suspend fun connect(source: SyncSource, remotePath: String)
    suspend fun disconnect()
    suspend fun close()
    suspend fun testConnection(source: SyncSource, remotePath: String): Boolean
    suspend fun listFiles(path: String): List<RemoteFile>
    suspend fun listDirectories(path: String): List<RemoteFile>
    /** 下载远端文件并写入 [sink]；不关闭 sink（调用方拥有生命周期）。 */
    suspend fun downloadTo(
        remotePath: String,
        sink: OutputStream,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean
    suspend fun deleteFile(remotePath: String): Boolean
}
```

SMB 实现（替换 257-297）：

```kotlin
override suspend fun downloadTo(
    remotePath: String, sink: OutputStream,
    onProgress: ((transferred: Long, total: Long) -> Unit)?
): Boolean = withContext(Dispatchers.IO) {
    val ctx = cifsContext ?: throw Exception("SMB未连接，请先执行connect()")
    try {
        val smbUrl = if (subPath.isNotEmpty()) "smb://$currentHost/$shareName/$subPath/$remotePath"
                     else "smb://$currentHost/$shareName/$remotePath"
        val smbFile = SmbFile(smbUrl, ctx)
        if (!smbFile.exists()) throw Exception("远程文件不存在: $remotePath")
        val totalSize = smbFile.length()
        val buffer = ByteArray(64 * 1024); var transferred = 0L
        smbFile.getInputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)            // 写入加密 sink（明文）
                transferred += read
                onProgress?.invoke(transferred, totalSize)
                currentCoroutineContext().ensureActive()
            }
            if (transferred < totalSize)
                throw java.io.IOException("Download truncated: $remotePath — expected $totalSize, got $transferred")
        }
        true
    } catch (e: Exception) {
        if (e is jcifs.smb.SmbException)
            throw Exception("下载失败 $remotePath: ${smbErrorMessage(e, currentHost)}", e)
        throw Exception("下载失败 $remotePath: ${e.message}", e)
    }
}
```

SFTP 实现（替换 158-195）：把 `FileOutputStream(localFile).use { output -> ... output.write ... }` 改为 `sftp.open(fullRemotePath).use { rf -> val totalSize = ...; var offset=0L; while(...) { rf.read(offset,buffer,0,len); sink.write(buffer,0,bytesRead); ... } }`，**不** `use{}` 包裹 sink。

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.sync.RemoteFileClientContractTest"`
Expected: PASS。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 9: SyncEngine 流式下载即加密 + 删除全部物理扫描状态

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt`（下载流程 419-579、reconcile 370-393、空间检查 336-337、删除 584-648）
- Modify: `app/src/test/java/cn/vectory/syncplayer/sync/SyncEngineTest.kt`（更新为新流程）

**设计要点：**
1. 使用现有 `VideoRepository.upsert(placeholder): Long` 取得自增 `id`；不新增不存在的 `insert` API。占位 `iv` 只作为临时状态，最终实体必须包含真实 8B IV。
2. 每次下载 attempt 都生成新 IV，打开截断的 `<id>.enc.tmp` 加密 sink；`downloadTo=false`、异常、短读或协程取消时关闭 sink、删除 tmp，并用现有 `deleteById(id)` 删除占位行。
3. 仅完整下载成功、sink 正常关闭且 `finalize(id)` 成功后，才经 `MediaUris.mediaUri(id)` 提取元数据和写入缩略图，再 `upsert` 完整实体。
4. 进程崩溃恢复：同步开始时清理 `.enc.tmp`；发现 final 密文但没有对应 DB 行时按 opaque-id 清理，不能从磁盘重建元数据。
5. 完整移除 RECONCILE：`dryRun` 物理检查、`executeSync` 的 `toReconcile` 分支、action 类型、日志和 UI 统计全部删除。
6. 普通删除、文件夹删除和 `DELETE_BOTH` 均按 `videoItemId` 操作，不得用单独 `relativePath` 删除。

**Acceptance Criteria:**
- `T9-C1**: 更新后的 `SyncEngineTest`：下载一个 action 后，mock 的 store 收到 `openEncryptSink`→`finalize` 调用序列，DB upsert 含 `volumeId` 与 `iv`（test: `downloadEncryptsAndRecords`）。
- `T9-C2**: 下载失败时 `store.cancel(id)` 被调用且占位行被删（test: `downloadFailureCancelsAndCleans`）。
- `T9-C3**: `SyncEngine.kt` 不再含 disk→DB reconcile（grep `resolveSyncFile.*exists` 扫描逻辑移除）；`compileDebugKotlin` 通过。

- [ ] **Step 1: Write the failing test（更新现有 SyncEngineTest）**

在 `SyncEngineTest.kt` 增加/改写：使用真实存在的 `upsert`/`deleteById` API。分别覆盖成功、`downloadTo=false`、异常、短读、协程取消和 finalize 失败；每次失败 attempt 都验证关闭 sink、`cancel(tmp)`、`deleteById`，且不会 finalize。另测 RECONCILE action 不再生成/执行。

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.sync.SyncEngineTest"`
Expected: FAIL（SyncEngine 仍走旧 File 流程 / reconcile）。

- [ ] **Step 3: Write minimal implementation**

重构 `SyncEngine.kt`：
- 构造注入 `encryptedMediaStore: EncryptedMediaStore`（由 Hilt 提供单例，见 Task 14）。
- `getLocalSyncRoot` 等基于 `storagePaths` 的旧逻辑删除；改用 `encryptedMediaStore`。
- 下载核心改写：

```kotlin
// 实现契约：必须按现有 Repository/DAO API 落实，不得使用不存在的 insert/delete。
suspend fun performDownload(action: SyncAction, relation: SyncRelation): Boolean {
    val placeholder = VideoItemEntity(
        syncRelationId = relation.id,
        relativePath = action.relativePath,
        parentPath = extractParentPath(action.relativePath),
        fileName = action.relativePath.substringAfterLast('/'),
        volumeId = encryptedMediaStore.volumeId,
        iv = ByteArray(8),                // 临时占位；成功后替换为真实 IV
        fileSize = action.fileSize,
        mediaType = deriveMediaType(action.relativePath),
    )
    val id = videoRepository.upsert(placeholder)
    repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
        val iv = CryptoScheme.newIv()
        try {
            val ok = encryptedMediaStore.openEncryptSink(id, iv).use { sink ->
                client.downloadTo(action.relativePath, sink) { t, total -> onProgress(t, total) }
            }
            check(ok) { "download returned false" }
            check(encryptedMediaStore.finalize(id)) { "finalize failed for $id" }
            val entity = videoRepository.getById(id)!!.copy(iv = iv)
            videoRepository.upsert(videoMetadataExtractor.extractAndSave(entity))
            return true
        } catch (cancel: CancellationException) {
            encryptedMediaStore.cancel(id); videoRepository.deleteById(id); throw cancel
        } catch (failure: Exception) {
            encryptedMediaStore.cancel(id)
            if (attempt == MAX_DOWNLOAD_ATTEMPTS - 1) {
                videoRepository.deleteById(id); throw failure
            }
        }
    }
    return false
}
```

- 删除 `dryRun`/`executeSync` 中所有 RECONCILE 物理路径逻辑（不只删除 370-393）。
- 删除路径按完整实体 id 调用 `encryptedMediaStore.delete(id)`、`deleteThumb(id)`、`playbackRepository.deleteByVideoId(id)`、`videoRepository.deleteById(id)`；`DELETE_BOTH` 再执行远端删除。
- 空间检查：`encryptedMediaStore.usableSpace()` 替代 `storagePaths.userdataRoot.usableSpace`（336-337）。

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.sync.SyncEngineTest"`
Expected: PASS。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
git diff --stat $(git rev-parse HEAD)
```

---

### Task 10: MediaContentProvider — 解密网关

**Files:**
- Create: `app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt`
- Create: `app/src/main/java/cn/vectory/syncplayer/provider/MediaUris.kt`
- Modify: `app/src/main/AndroidManifest.xml`（注册 provider + 权限）
- Create: `app/src/main/res/values/...`（权限声明，见代码）
- Test: `app/src/androidTest/java/cn/vectory/syncplayer/provider/MediaContentProviderTest.kt`（instrumented）

**设计要点：** `openFile` 解析并严格校验 authority/path/id → DAO 取实体；实体不存在或 `entity.volumeId != store.volumeId` 时抛 `FileNotFoundException`，不得从错误活跃卷读取。`openFile` 中的句柄打开若 API 为 suspend，使用 `runBlocking(Dispatchers.IO)`，不得在主线程执行阻塞 I/O；callback 使用专用后台 `HandlerThread`（禁止主线程 Handler）。`thumb` 读取 `ver‖iv‖ciphertext+tag` 后解密并 pipe 返回。`getType` 统一调用 `getMimeType(entity.fileName)` 返回准确 MIME，`query` 对未知实体返回空 Cursor，不使用 `!!`。

**Acceptance Criteria:**
- `T10-C1**: `MediaUris.mediaUri(42).toString()` == `"content://cn.vectory.syncplayer.media/media/42"`；`thumbUri(7)` 对应（test）。
- `T10-C2**: instrumented：预置一个加密文件（用 `EncryptedMediaStore.writeThumb`/手动加密）后，`contentResolver.openInputStream(mediaUri(id))` 读出的字节等于原始明文；`getType` 返回 `video/mp4`（当 mediaType=VIDEO 且文件名 .mp4）。
- `T10-C3**: instrumented：`query(mediaUri, [OpenableColumns.DISPLAY_NAME, SIZE])` 返回真实 fileName 与 fileSize。
- `T10-C4**: Manifest 声明 signature 权限 `cn.vectory.syncplayer.permission.READ_MEDIA`，provider exported+grantUriPermission；`compileDebugKotlin` + `assembleDebug` 通过。

- [ ] **Step 1: Write the failing test（骨架 + URI 单测）**

`MediaUrisTest`（纯 JVM）：

```kotlin
// app/src/test/java/cn/vectory/syncplayer/provider/MediaUrisTest.kt
package cn.vectory.syncplayer.provider

import org.junit.Assert.*
import org.junit.Test

class MediaUrisTest {
    @Test fun uriShapes() {
        assertEquals("content://cn.vectory.syncplayer.media/media/42", MediaUris.mediaUri(42).toString())
        assertEquals("content://cn.vectory.syncplayer.media/thumb/7", MediaUris.thumbUri(7).toString())
    }
}
```

instrumented 骨架见 Step 3 后补。

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.provider.MediaUrisTest"`
Expected: FAIL（`MediaUris` 未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/cn/vectory/syncplayer/provider/MediaUris.kt
package cn.vectory.syncplayer.provider

import android.content.ContentUris
import android.net.Uri

object MediaUris {
    const val AUTHORITY = "cn.vectory.syncplayer.media"
    private const val PATH_MEDIA = "media"
    private const val PATH_THUMB = "thumb"
    val BASE = Uri.parse("content://$AUTHORITY")!!
    fun mediaUri(id: Long): Uri = ContentUris.withAppendedId(Uri.parse("content://$AUTHORITY/$PATH_MEDIA"), id)
    fun thumbUri(id: Long): Uri = ContentUris.withAppendedId(Uri.parse("content://$AUTHORITY/$PATH_THUMB"), id)
    fun isMedia(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == PATH_MEDIA
    fun isThumb(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == PATH_THUMB
    fun idOf(uri: Uri): Long = ContentUris.parseId(uri)
}
```

```kotlin
// app/src/main/java/cn/vectory/syncplayer/provider/MediaContentProvider.kt
package cn.vectory.syncplayer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import cn.vectory.syncplayer.crypto.DecryptingFdCallback
import cn.vectory.syncplayer.data.local.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileOutputStream

@EntryPoint @InstallIn(SingletonComponent::class)
interface MediaProviderEntryPoint {
    fun appDatabase(): AppDatabase
    fun mediaStore(): cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
}

class MediaContentProvider : ContentProvider() {
    private lateinit var storageManager: StorageManager
    private lateinit var ioThread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate(): Boolean {
        storageManager = context!!.getSystemService(StorageManager::class.java)
        ioThread = HandlerThread("media-provider-io").also { it.start() }
        handler = Handler(ioThread.looper)
        return true
    }
    private fun ep() = EntryPointAccessors.fromApplication(context!!, MediaProviderEntryPoint::class.java)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val id = MediaUris.idOf(uri)
        val store = ep().mediaStore()
        val entity = ep().appDatabase().videoItemDao().getByIdSync(id)
            ?: throw java.io.FileNotFoundException("media id=$id not found")
        check(entity.volumeId == store.volumeId) {
            "media id=$id belongs to volume=${entity.volumeId}, active=${store.volumeId}"
        }
        return if (MediaUris.isMedia(uri)) {
            val handle = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { store.openCiphertextRead(id) }
            val key = store.contentKey()
            val callback = DecryptingFdCallback(handle, key, entity.iv, entity.fileSize)
            storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
        } else {
            // thumb：小文件，全量解密后 pipe
            val bytes = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { store.readThumb(id) } ?: return null
            val pfd = openPipeHelper(uri, mode, null, bytes) { output, _, _, data ->
                FileOutputStream(output.fileDescriptor).use { it.write(data as ByteArray) }
            }
            pfd
        }
    }

    override fun getType(uri: Uri): String? {
        val id = MediaUris.idOf(uri); val e = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return null
        return cn.vectory.syncplayer.ui.util.getMimeType(e.fileName)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cur = MatrixCursor(cols, 1)
        val id = MediaUris.idOf(uri)
        val e = ep().appDatabase().videoItemDao().getByIdSync(id) ?: return cur
        val row = cur.newRow()
        cols.forEach { c ->
            row.add(when (c) {
                OpenableColumns.DISPLAY_NAME -> e.fileName
                OpenableColumns.SIZE -> e.fileSize
                else -> null
            })
        }
        return cur
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
```

`AndroidManifest.xml`（`<application>` 内）：

```xml
<permission android:name="cn.vectory.syncplayer.permission.READ_MEDIA"
            android:protectionLevel="signature" />
<provider
    android:name="cn.vectory.syncplayer.provider.MediaContentProvider"
    android:authorities="cn.vectory.syncplayer.media"
    android:exported="true"
    android:grantUriPermissions="true"
    android:readPermission="cn.vectory.syncplayer.permission.READ_MEDIA" />
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.provider.MediaUrisTest"`（PASS）；instrumented `MediaContentProviderTest` 见 Task 15 真机手测。
Run: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug` → 期望 APK 构建通过。

- [ ] **Step 5: Record diff**

```bash
git rev-parse HEAD
git diff --stat $(git rev-parse HEAD)
```

---

### Task 11: 消费者改造 — ExoPlayer / SeekPreview / AudioGain

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt`（129-141）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/SeekPreviewExtractor.kt`（50-62、92、166-183、缓存键）
- Modify: `app/src/main/java/cn/vectory/syncplayer/audio/AudioGainAnalyzer.kt`（34）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt`（若持有 path）

**改造：**
- PlayerScreen：`MediaItem.fromUri(MediaUris.mediaUri(item.id))`；删 `File(filePath).exists()` 检查（改为依赖 provider）。
- SeekPreviewExtractor：`beginSession`/`extractFallback` 用 `setDataSource(context, MediaUris.mediaUri(id))`；`CacheKey.filePath`→`videoId: Long`；签名 `beginSession(videoId, context)`。需在构造/调用处注入 `Context`。
- AudioGainAnalyzer：`MediaExtractor.setDataSource(context, MediaUris.mediaUri(id))`。

**Acceptance Criteria:**
- `T11-C1**: `compileDebugKotlin` 通过。
- `T11-C2**: `SeekPreviewExtractor` 不再含 `setDataSource(filePath: String)`（grep 验证）；缓存键改为 `videoId`。
- `T11-C3**: PlayerScreen 的 `MediaItem` 来自 `MediaUris.mediaUri(id)`（grep 验证无 `Uri.fromFile`）。

- [ ] **Step 1-5: 标准 TDD 循环（此任务偏机械改造，无新逻辑可单测；以编译 + grep 为验收）**

```bash
git rev-parse HEAD
# 改造后：
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
rg -n "setDataSource\(.*filePath" app/src/main/java/cn/vectory/syncplayer/player/   # 期望 0 命中
rg -n "Uri\.fromFile" app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt # 期望 0 命中
git diff --stat $(git rev-parse HEAD)
```

---

### Task 12: 消费者改造 — 缩略图 / 图片 / Metadata 提取

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/ui/components/Thumbnail.kt`（签名 `thumbnailPath`→`videoItemId: Long?`，`AsyncImage(MediaUris.thumbUri(id))`）
- Modify: 所有 `Thumbnail(...)` 调用点（grep `Thumbnail(` 后逐处传 id）
- Modify: `app/src/main/java/cn/vectory/syncplayer/imageviewer/ImageViewerScreen.kt`（293）
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/VideoMetadataExtractor.kt`（28,35,57-61,89）
- Modify: `app/src/main/java/cn/vectory/syncplayer/sync/ImageMetadataExtractor.kt`

**改造：**
- `Thumbnail(videoItemId, isVideo, isImage)`：`AsyncImage(model = ImageRequest.Builder(ctx).data(videoItemId?.let { MediaUris.thumbUri(it) }).size(320).build())`。
- ImageViewerScreen：`AsyncImage(MediaUris.mediaUri(entity.id))`。
- VideoMetadataExtractor：读源改 `retriever.setDataSource(context, MediaUris.mediaUri(videoItem.id))`；缩略图写入改 `encryptedMediaStore.writeThumb(id, jpgBytes)`（构造注入 `EncryptedMediaStore`）；`repairThumbnailIfNeeded` 同理。返回实体不再带 `thumbnailPath`（字段已删）。
- ImageMetadataExtractor 同理。

**Acceptance Criteria:**
- `T12-C1**: `compileDebugKotlin` 通过。
- `T12-C2**: `rg "thumbnailPath" app/src/main` 仅剩迁移期无引用（实体已删，期望 0 业务引用）。
- `T12-C3**: `VideoMetadataExtractor` 用 `setDataSource(context, uri)` 且缩略图经 `encryptedMediaStore.writeThumb`（grep 验证）。

- [ ] **Step 1-5: TDD 循环（机械改造，编译 + grep 验收）**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
rg -n "thumbnailPath" app/src/main/java   # 期望 0 命中（实体字段已删）
rg -n "File\(.*localAbsolutePath" app/src/main/java   # 期望 0 命中
git diff --stat $(git rev-parse HEAD)
```

---

### Task 13: 删除/分享路径 + 移除 DocumentsProvider 与孤儿扫描

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt`（deleteFile 99-120、deleteFolder 161-201、buildShareIntent 122-142）
- Delete: `app/src/main/java/cn/vectory/syncplayer/provider/SyncDocumentsProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`（删 DocumentsProvider 注册）
- Modify: `app/src/main/java/cn/vectory/syncplayer/data/repository/UserPreferencesRepository.kt`（删 `saf_provider_enabled`）
- Modify: `app/src/main/java/cn/vectory/syncplayer/settings/*`（删 SAF 开关 UI）
- Modify: `app/src/main/java/cn/vectory/syncplayer/browser/BrowserViewModel.kt`（删 321-370、650-681 的物理目录/孤儿/手动导入逻辑）
- Modify: `app/src/main/java/cn/vectory/syncplayer/settings/MetadataRebuildViewModel.kt`（移除 userdataRoot/localAbsolutePath/thumbnailPath 扫描，按 DB+URI 重建）
- Modify: `app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt`（按 id/URI 调用分析器）
- Modify: `app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt`（删除或改造 getVideoFilePath）
- Modify: `app/src/main/java/cn/vectory/syncplayer/ui/components/VideoGridItem.kt`
- Modify: `app/src/main/java/cn/vectory/syncplayer/ui/components/FolderGridItem.kt`
- Modify: `app/src/main/java/cn/vectory/syncplayer/browser/SearchScreen.kt`, `BrowserScreen.kt`, `SearchViewModel.kt`
- Modify: `app/src/main/java/cn/vectory/syncplayer/data/local/dao/VideoItemDao.kt`（移除 thumbnailPath DTO/projection，补按 id/文件夹实体查询）
- Delete: `app/src/main/res/xml/filepaths.xml`（确认 FileProvider 不再被其它用途引用后）
- Modify: `app/src/test/java/cn/vectory/syncplayer/provider/DocumentIdTest.kt`（若引用被删类，删除/调整）

**改造：**
- `deleteFile`：调用方传 `videoItemId`（或同时传 `syncRelationId`）；禁止只用 `getByRelativePath`，因为该路径跨 relation 不唯一。按 id 删除播放记录、密文、缩略图和 DB 行。
- `deleteFolder`：使用现有 `getItemsInFolder()` 或新增明确的 suspend list query 获取完整实体列表，逐个按 id 删除；不得调用不存在的 `getByFolderPath`，也不再 `dir.deleteRecursively()`。
- `buildShareIntent`：`val uri = MediaUris.mediaUri(video.id); Intent(ACTION_SEND){ type=getMimeType(video.fileName); putExtra(EXTRA_STREAM, uri); addFlags(FLAG_GRANT_READ_URI_PERMISSION) }`。不再用 FileProvider。
- 删 `SyncDocumentsProvider.kt` + Manifest provider 节点 + `saf_provider_enabled` pref + 设置开关 + `DocumentIdTest`（若专测该 provider）。

**Acceptance Criteria:**
- `T13-C1**: `compileDebugKotlin` + `assembleDebug` 通过。
- `T13-C2**: `rg "SyncDocumentsProvider" app/src/main` 期望 0 命中；`rg "saf_provider_enabled" app/src/main` 期望 0。
- `T13-C3**: `FileOperationsUseCase.buildShareIntent` 使用 `MediaUris.mediaUri` + `FLAG_GRANT_READ_URI_PERMISSION`，无 `FileProvider.getUriForFile`（grep）。
- `T13-C4**: `BrowserViewModel` 不再含孤儿扫描/手动导入（grep `userdataRoot` 在该文件期望仅剩无害引用或 0）。

- [ ] **Step 1-5: TDD 循环（机械改造，编译 + grep 验收；`buildShareIntent` 可补单测：mock repo 返回实体，断言 intent 的 `EXTRA_STREAM == MediaUris.mediaUri(id)` 且含 flag）**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
rg -n "SyncDocumentsProvider|saf_provider_enabled" app/src/main   # 0
rg -n "FileProvider.getUriForFile" app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt   # 0
git diff --stat $(git rev-parse HEAD)
```

---

### Task 14: 存储位置设置 + MediaVolume/Hilt 装配

**Files:**
- Modify: `app/src/main/java/cn/vectory/syncplayer/data/repository/UserPreferencesRepository.kt`（加 `media_storage_tree_uri`）
- Modify: `app/src/main/java/cn/vectory/syncplayer/di/SyncModule.kt`（提供 `MediaVolume`/`EncryptedMediaStore` 单例）
- Create: `app/src/main/java/cn/vectory/syncplayer/storage/volume/MediaVolumeProvider.kt`（按偏好选 FileVolume/SafVolume，含撤销检测 R4）
- Modify: `app/src/main/java/cn/vectory/syncplayer/settings/SettingsScreen.kt` + `SettingsViewModel.kt`（"媒体存储位置"入口）

**设计要点：**
- 偏好 `media_storage_tree_uri`(String?)：null→`FileVolume(File(filesDir,"userdata"))`；非空→`SafVolume(ctx, Uri.parse(treeUri))`。
- `EncryptedMediaStore` 单例包装当前卷 + `MediaCryptoMaster.masterKey()`。
- **v1 单活跃卷**：写偏好后重启 App 重建 `MediaVolume`/`EncryptedMediaStore`；旧记录因 `volumeId` 校验而不可读，设置 UI 必须提示清空当前卷并全量重同步。不得宣称旧卷并存可读。
- SAF tree URI 失效（R4）：访问失败时回退内部卷 + 提示重新授权。

**Acceptance Criteria:**
- `T14-C1**: 偏好为 null 时 `MediaVolumeProvider` 返回 `FileVolume`；非空时返回 `SafVolume`（test 用可注入偏好）。
- `T14-C2**: `compileDebugKotlin` + `assembleDebug` 通过。
- `T14-C3**: SettingsScreen 含"媒体存储位置"入口，点击拉 `ACTION_OPEN_DOCUMENT_TREE` 并 `takePersistableUriPermission`（代码审查/grep）。

- [ ] **Step 1-5: TDD 循环（`MediaVolumeProvider` 加单测；UI 机械）**

```bash
git rev-parse HEAD
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests "cn.vectory.syncplayer.storage.volume.MediaVolumeProviderTest"
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug
git diff --stat $(git rev-parse HEAD)
```

---

### Task 15: 整体构建 + 集成验证（真机手测清单）

**Files:**
- Create: `docs/ocmar/specs/2026-07-25-encrypted-storage-manual-verify.md`（真机手测清单，非代码）

**Acceptance Criteria（Final-only，跨任务）：**
- `T15-C1**: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug` 成功产出 APK，无编译错误。
- `T15-C2**: `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest` 全绿（覆盖 Task 1-9,14 的单测）。
- `T15-C3**: Room schema `app/schemas/.../18.json` 存在且与迁移 DDL 一致。
- `T15-C4**（真机）: 安装后选内部目录→同步一个视频→ExoPlayer 播放可 seek→拖动预览取帧正常→缩略图渲染→增益分析可跑→分享到第三方播放器可透传播放。
- `T15-C5**（真机）: 选外置 SD 卡目录（SAF）→重复 T15-C4 全流程通过。
- `T15-C6**（真机）: 用文件管理器查看选定目录→仅见 `<id/1000>/<id>.enc` 密文与 `thumbs/<id>.enc`，无明文/真名（验证 D8 + 内容加密）。
- `T15-C7**（真机）: 重启 App 后播放既有文件正常（SAF tree URI 持久化、密钥解包）。
- `T15-C8**: spec §13 风险登记的 R1/R4/R6 在真机验证中无复现或已降级处理。

- [ ] **Step 1: 跑全量构建与单测**

```bash
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew :app:testDebugUnitTest
ls app/schemas/cn/vectory/syncplayer/data/local/AppDatabase/18.json
```

- [ ] **Step 2: 写真机手测清单**（把 T15-C4..C8 落成可勾选步骤文档）

- [ ] **Step 3: Record final diff**

```bash
git rev-parse HEAD
git diff --stat <baseline-of-task1>
```

---

## Criterion Ownership Matrix

| Criterion ID | Spec requirement | Owner task | Cross-task deps | Verification (command/test + expected) | Final-only? |
|---|---|---|---|---|---|
| T1-C1..C5 | §5.2 CTR seek 数学 / §5.4 GCM 缩略图 / IV 唯一 | Task 1 | — | `:app:testDebugUnitTest CryptoSchemeTest` → PASS | N |
| T2-C1..C3 | §5.1 主密钥 Keystore KEK 包裹软件密钥 | Task 2 | Task 1(HKDF) | `MediaCryptoMasterTest` → PASS + compile | N |
| T3-C1..C4 | §4.1 MediaVolume + FileVolume / §5.3 路径派生 | Task 3 | — | `FileVolumeTest` → PASS | N |
| T4-C1..C3 | §4.1 SafVolume / R8 可用空间降级 | Task 4 | Task 3 | assembleAndroidTest 编译 + 真机(Task15) | Y(C1,C2) |
| T5-C1..C3 | §3.1 EncryptedMediaStore 编排 | Task 5 | 1,3 | `EncryptedMediaStoreTest` → PASS | N |
| T6-C1..C3 | §5.5 代理 FD 解密器 / seek 正确性 | Task 6 | 1,3 | `RangeDecryptorTest` → PASS | N |
| T7-C1..C3 | §8 schema v17→v18 破坏性 / FK 连带 / §8.1 列变更 | Task 7 | — | `Migration17To18Test` → PASS + 18.json 存在 | N |
| T8-C1..C3 | §7.1 downloadTo 不关 sink / SMB+SFTP | Task 8 | — | `RemoteFileClientContractTest` → PASS | N |
| T9-C1..C3 | §7 流式下载即加密 / 删 reconcile / 空间检查 | Task 9 | 5,8 | `SyncEngineTest` → PASS + grep 无 reconcile | N |
| T10-C1..C4 | §6 MediaContentProvider 网关 / 权限 / getType/query | Task 10 | 5,6,7 | `MediaUrisTest` PASS + assembleDebug + 真机(Task15) | Y(C2,C3) |
| T11-C1..C3 | §9 ExoPlayer/SeekPreview/AudioGain → content:// | Task 11 | 10 | compile + grep | N |
| T12-C1..C3 | §9 Thumbnail/ImageViewer/Extractors → content:// | Task 12 | 10 | compile + grep | N |
| T13-C1..C4 | §9 删除/分享 + §删除项(DocumentsProvider/孤儿扫描/reconcile) | Task 13 | 5,10 | compile + grep + assembleDebug | N |
| T14-C1..C3 | §4.2/§10 存储位置偏好 + UI + Hilt 装配 / R4 回退 | Task 14 | 2,3,4,5 | `MediaVolumeProviderTest` + assembleDebug | N |
| T15-C1..C8 | §11 验证策略整体 / §13 风险 R1,R4,R6 | Task 15 | 全部 | assembleDebug + testDebugUnitTest + 真机清单 | Y |

---

## 附录 A：跨任务类型一致性快查

- 主密钥类型统一 `javax.crypto.SecretKey`（软件 AES-256）；派生键经 `CryptoScheme.deriveContentKey/deriveThumbKey`。
- `iv` 字段：DB 存 `ByteArray`（Room BLOB），DAO 透传；Provider 从实体取 `entity.iv` 喂 `DecryptingFdCallback`。
- 路径派生只经 `MediaPaths.mediaRelPath/thumbRelPath`，禁止它处拼 `<id>.enc`。
- URI 只经 `MediaUris.mediaUri/thumbUri`，禁止手拼 authority。
- `EncryptedMediaStore` 是唯一写密文入口；`RemoteFileClient.downloadTo` 的 sink 由 store 的 `openEncryptSink` 提供。
- `ProxyFileDescriptorCallback` 仅在 `MediaContentProvider.openFile` 创建；逻辑解密经 `RangeDecryptor`。

## 附录 B：风险与对策（衔接 spec §13）

- R1（OEM/SAF seek 异常）→ T15-C5 真机 SD 卷覆盖；SafVolume.openRead 用 `Os.lseek/read`。
- R4（tree URI 撤销）→ T14 `MediaVolumeProvider` 访问失败回退内部卷 + 提示。
- R6（半加密 `.enc.tmp`）→ `finalize` 原子改名；App 启动扫描清理残留 `.enc.tmp`（Task 9/14 落实）。
- R7（单 handler 线瓶颈）→ v1 接受；`DecryptingFdCallback` 预留换 executor 的钩子。
