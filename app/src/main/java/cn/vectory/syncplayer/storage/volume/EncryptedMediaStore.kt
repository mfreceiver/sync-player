package cn.vectory.syncplayer.storage.volume

import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Result of [EncryptedMediaStore.checkExistence]. */
sealed class MediaExistence {
    data object EXISTS : MediaExistence()
    data object NOT_FOUND : MediaExistence()
    data class IO_ERROR(val reason: String) : MediaExistence()
}

/** BUG-007：tmp 密文存在性三态探测结果。 */
enum class TmpProbeState { PRESENT, ABSENT, UNKNOWN }

/**
 * Encrypted media I/O against the **current** active [MediaVolume].
 *
 * Volume is resolved on every operation via [MediaVolumeProvider.current] so that
 * switching storage in Settings takes effect without process restart.
 */
class EncryptedMediaStore(
    private val mediaVolumeProvider: MediaVolumeProvider,
    private val masterProvider: () -> SecretKey,
) {
    private val masterKey: SecretKey get() = masterProvider()
    fun contentKey() = CryptoScheme.deriveContentKey(masterKey)
    private fun thumbKey() = CryptoScheme.deriveThumbKey(masterKey)

    private suspend fun volume(): MediaVolume = mediaVolumeProvider.current()

    /**
     * Active volume id. Resolved live (not cached at construction).
     * Uses [runBlocking] so sync call sites (ContentProvider, SyncEngine field writes) stay simple.
     */
    val volumeId: String
        get() = runBlocking { volume().volumeId }

    private fun tmpMedia(id: Long) = MediaPaths.mediaRelPath(id) + MediaPaths.TMP_SUFFIX
    private fun finalMedia(id: Long) = MediaPaths.mediaRelPath(id)

    /**
     * AEAD v2 encrypting sink for media id [id]. Writes the v2 header immediately, then buffers
     * 64 KiB plaintext chunks sealed with AES/GCM. [close] flushes the final short chunk and
     * throws `IOException` if the bytes written ≠ [plainLength] (so a wrong-length file is never
     * finalized). Caller must [finalize] after a successful close to atomically promote `.tmp`.
     */
    suspend fun openEncryptSink(id: Long, iv: ByteArray, plainLength: Long): java.io.OutputStream {
        require(iv.size == CryptoScheme.IV_LEN) { "iv must be ${CryptoScheme.IV_LEN} bytes" }
        require(plainLength >= 0) { "plainLength must be non-negative" }
        // Capture volume once: avoids race if user switches storage between openWrite and catch.
        val vol = volume()
        val sink = try {
            vol.openWrite(tmpMedia(id))
        } catch (e: java.io.IOException) {
            // SAF createDocument/openOutputStream 返回 null 时 SafVolume 抛 IOException；
            // 包装成 STORAGE_ERROR 让 UI 显示准确文案而非 UNKNOWN/类名。
            throw SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD, path = tmpMedia(id), cause = e)
        }
        // The wrapper must own `sink` from here on: encodeHeader() runs during ChunkedEncryptSink's
        // property initialization and rejects lengths past the implementation cap, i.e. it can throw
        // *before* the constructor body's own try/catch exists. Without this guard the already-open
        // underlying stream would leak, since the caller never receives an object to close.
        return try {
            CryptoScheme.encryptStream(contentKey(), iv, plainLength, sink)
        } catch (e: Throwable) {
            try { sink.close() } catch (closeError: Exception) { e.addSuppressed(closeError) }
            // NonCancellable: ensure stub cleanup runs even if the coroutine is being cancelled
            // (CancellationException would otherwise skip the suspend delete, leaving a 0-byte file).
            try { withContext(NonCancellable) { vol.delete(tmpMedia(id)) } } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Two-arg overload is unsupported under AEAD v2 (the format needs the plaintext length up-front
     * to write the header). Always throws at runtime; callers must use [openEncryptSink] with
     * `plainLength`. Kept as a callable signature so call sites that have not yet migrated still
     * compile (H1 lane switches the SyncEngine call site to the three-arg form).
     */
    suspend fun openEncryptSink(id: Long, iv: ByteArray): java.io.OutputStream =
        throw IllegalStateException(
            "openEncryptSink(id, iv) is unsupported under AEAD v2; pass plaintext length via openEncryptSink(id, iv, plainLength)"
        )

    suspend fun finalize(id: Long): Boolean = volume().rename(tmpMedia(id), finalMedia(id))

    suspend fun cancel(id: Long) {
        volume().delete(tmpMedia(id))
    }

    /**
     * BUG-007：tmp 密文存在性三态探测。ABSENT 仅在「已进入过 finalize」语境下**且**底层
     * [MediaVolume.existsStrict] 返回了完整、严格的「确认未命中」时可解读为「rename 已消费 tmp」；
     * UNKNOWN = 探测自身失败/结果不完整（查询异常 / null cursor / SAF 加载中），调用方必须
     * 保守处理（只删行、不删 final）。与既有 [checkExistence] 的三态契约同构。
     *
     * CE 传播契约：对普通调用方 rethrow CE 不变（不全局吞取消）；**清理语境
     * （SyncDownloadPipeline.cleanUpAttempt）的调用点由调用方单独 try/catch 收敛 UNKNOWN**——
     * 本函数不因清理场景改契约。
     */
    suspend fun probePendingTmp(id: Long): TmpProbeState = try {
        if (volume().existsStrict(tmpMedia(id))) TmpProbeState.PRESENT else TmpProbeState.ABSENT
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TmpProbeState.UNKNOWN
    }

    suspend fun openCiphertextRead(id: Long): SeekableReadHandle = volume().openRead(finalMedia(id))

    /**
     * Finalize 后的可读性探针（F14/T10 增强）：O(1) 三方一致性检查——
     *
     *  1. **完整 16B header 解析**：复用 [CryptoScheme.decodeHeader] 的严格校验
     *     （magic/version/reserved/chunkSizeKiB/明文长非负），不重复定义任何格式常量；
     *  2. **实际密文文件大小** == [CryptoScheme.cipherLengthFor]（header 声明的明文长），
     *     拦截 finalize→提交窗口内的截断/追加损坏；
     *  3. **可信预期明文长度** [expectedPlaintextLength]（调用方显式传入；pipeline 侧为
     *     远端源文件大小 `action.fileSize`）== header 声明的明文长——不依赖 DB 是否已有
     *     最终 metadata（upsert 前占位行 fileSize=0，不可作为预期来源）。
     *
     * 用于在 finalize 成功后立即检测该卷是否真的能解密读取（SAF 不支持 seek 时 openRead
     * 抛 UnsupportedOperationException；密文损坏/截断/内容错配时三方检查失败）。任何失败
     * 都抛异常（绝不静默通过、绝不返回脏明文），让调用方（SyncDownloadPipeline）的既有
     * finalize 补偿链包装为 STORAGE_ERROR 并单点回滚。
     *
     * 注意：只做 header 解析 + 长度算术，不解密任何 chunk（逐 chunk GCM 认证仍由
     * RangeDecryptor 在播放路径上兜底，key/iv 已在 DB 里，不在本探针职责内）。
     */
    suspend fun verifyReadable(id: Long, expectedPlaintextLength: Long) {
        require(expectedPlaintextLength >= 0) {
            "expectedPlaintextLength must be non-negative: $expectedPlaintextLength"
        }
        val h = openCiphertextRead(id)
        try {
            // 循环满读 16B header：read 可能部分返回，EOF（n<=0）即视为截断/损坏
            val headerBuf = ByteArray(CryptoScheme.HEADER_SIZE)
            var off = 0
            while (off < headerBuf.size) {
                val n = h.read(headerBuf, off, headerBuf.size - off)
                if (n <= 0) {
                    throw java.io.IOException("read probe short read: got $off/${headerBuf.size} bytes")
                }
                off += n
            }
            val header = CryptoScheme.decodeHeader(headerBuf)
            if (header.plaintextLength != expectedPlaintextLength) {
                throw java.io.IOException(
                    "read probe: header plaintext length ${header.plaintextLength} != expected $expectedPlaintextLength"
                )
            }
            val expectedCipherLen = CryptoScheme.cipherLengthFor(header.plaintextLength)
            val actualSize = h.size()
            if (actualSize != expectedCipherLen) {
                throw java.io.IOException(
                    "read probe: ciphertext size $actualSize != expected $expectedCipherLen (plaintext ${header.plaintextLength})"
                )
            }
        } finally {
            try { h.close() } catch (_: Exception) {}
        }
    }

    suspend fun delete(id: Long): Boolean = volume().delete(finalMedia(id))

    suspend fun exists(id: Long): Boolean = volume().exists(finalMedia(id))

    suspend fun thumbExists(id: Long): Boolean = volume().exists(MediaPaths.thumbRelPath(id))

    /**
     * Three-way existence check for rebuild/orphan scan.
     * - [MediaExistence.EXISTS] / [MediaExistence.NOT_FOUND]: definitive
     * - [MediaExistence.IO_ERROR]: volume access failed (permission/SAF/IO); caller must not treat as orphan
     */
    suspend fun checkExistence(id: Long): MediaExistence = try {
        if (volume().exists(finalMedia(id))) MediaExistence.EXISTS else MediaExistence.NOT_FOUND
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MediaExistence.IO_ERROR(e.javaClass.simpleName)
    }

    fun usableSpace(): Long = runBlocking { volume().usableSpace() }

    suspend fun writeThumb(id: Long, plain: ByteArray) {
        val encoded = CryptoScheme.encryptThumb(thumbKey(), plain)
        volume().openWrite(MediaPaths.thumbRelPath(id)).use { it.write(encoded) }
    }

    suspend fun readThumb(id: Long): ByteArray? {
        val vol = volume()
        if (!vol.exists(MediaPaths.thumbRelPath(id))) return null
        val buf = java.io.ByteArrayOutputStream()
        // 缩略图小文件，全量读；处理短读以获取完整数据
        buf.use { b ->
            vol.openRead(MediaPaths.thumbRelPath(id)).use { h ->
                val expectedSize = h.size()
                require(expectedSize in 29..1_048_576) { "invalid thumbnail size: $expectedSize" }
                val tmp = ByteArray(expectedSize.toInt())
                var totalRead = 0
                while (totalRead < expectedSize) {
                    val n = h.read(tmp, totalRead, (expectedSize - totalRead).toInt())
                    if (n <= 0) throw java.io.EOFException(
                        "Truncated thumbnail read: expected $expectedSize bytes, got $totalRead"
                    )
                    totalRead += n
                }
                b.write(tmp)
            }
        }
        val encoded = buf.toByteArray()
        require(encoded.size <= 1_048_576) { "thumbnail too large: ${encoded.size}" }
        return CryptoScheme.decryptThumb(thumbKey(), encoded)
    }

    suspend fun deleteThumb(id: Long): Boolean = volume().delete(MediaPaths.thumbRelPath(id))

    // ---- legacy v1 ciphertext purge (one-shot, post-v2-upgrade) ----

    /** Marker file written at the current volume root once [purgeLegacyIfNeeded] has run there. */
    private val purgeMarker = ".spm2_purged"

    /**
     * One-shot cleanup of pre-AEAD-v2 ciphertext orphans on the **current** volume.
     *
     * Deletes, strictly within MediaPaths-produced locations only:
     *  - every `<bucket>/<id>.enc` media file whose 4-byte header is **confirmed** to be a
     *    non-`"SPM2"` legacy v1 header (full magic-length read succeeded and mismatched),
     *  - every encrypted thumbnail under the `thumbs` directory (thumbnails are derived; they
     *    regenerate on the next sync/rebuild).
     *
     * NOTE: do not write a literal slash-star glob such as "thumbs" + slash + star inside this
     * KDoc — Kotlin block comments nest, so it would open a nested comment and swallow the
     * closing delimiter.
     *
     * **Deliberately NOT deleted** (fail-closed):
     *  - `*.enc.tmp` partial downloads. The one-shot startup purge is invoked from
     *    `SyncPlayerApp` on a background coroutine under `storageOperationMutex` (the same
     *    mutex that serializes sync/delete workflows), so it does not race an active download.
     *    A leftover `.tmp` is kept regardless — a strict fail-closed / anti-mistaken-delete
     *    strategy: it is harmlessly overwritten/truncated on the next [openEncryptSink] for
     *    that id, so deleting it buys nothing but would reintroduce a delete-while-writing
     *    race on any future path that forgets the mutex. See task 1.
     *  - any `.enc` file whose header could not be read in full (transient IO error, SAF
     *    permission flap, truncated/short file). Only a *confirmed* non-v2 header authorizes
     *    deletion; a read failure preserves the file so a temporarily-unreadable v2 file is
     *    never lost. See task 4 / [isLegacyOrphan].
     *  - any bucket/file that does not match MediaPaths' layout exactly: bucket must be a
     *    non-negative long, filename must be exactly `<digits>.enc` with no sign/whitespace,
     *    and `id / 1000 == bucket`. See tasks 2-3 / [parseFinalMediaName].
     *
     * v2 media files are always preserved. Idempotent per volume: a marker file
     * [.spm2_purged] at the volume root guards re-scanning.
     *
     * Scope is intentionally narrow: only the numeric bucket directories produced by
     * [MediaPaths.mediaRelPath] (`0/`, `1/`, …) and the `thumbs/` directory are touched; no
     * other user data is enumerated or deleted.
     *
     * **Wiring:** invoked once from `SyncPlayerApp.onCreate` through
     * `purgeLegacyCiphertext()`, on a background coroutine under
     * `SyncEngine.storageOperationMutex`. Returns the number of files deleted.
     */
    suspend fun purgeLegacyIfNeeded(): Int {
        val vol = volume()
        if (vol.exists(purgeMarker)) return 0
        var deleted = 0
        for (entry in vol.list("")) {
            // Bucket dirs from MediaPaths.mediaRelPath are "${id / 1000}" — always a
            // non-negative integer. toLongOrNull (not toInt) handles large ids without overflow;
            // reject anything that is not a provably-valid bucket (incl. negative names).
            val bucket = if (entry.isNotEmpty() && entry.all { it in '0'..'9' }) {
                entry.toLongOrNull()
            } else {
                null
            }
            if (bucket != null && bucket >= 0) {
                deleted += purgeBucket(vol, bucket)
            } else if (entry == "thumbs") {
                deleted += purgeThumbs(vol)
            }
            // any other top-level entry is outside MediaPaths' layout — ignored
        }
        // place marker so we never scan again on this volume; best-effort
        try {
            vol.openWrite(purgeMarker).use { it.write(byteArrayOf(1)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // marker write is best-effort
        }
        return deleted
    }

    /**
     * Purge confirmed-legacy media files inside bucket directory [bucket].
     * [bucket] is the already-validated non-negative `id / 1000` value.
     *
     * Tmp files are intentionally *not* deleted here (see [purgeLegacyIfNeeded]); they are
     * simply skipped by [parseFinalMediaName] not matching the `<id>.enc` final-media shape.
     */
    private suspend fun purgeBucket(vol: MediaVolume, bucket: Long): Int {
        val bucketDir = bucket.toString()
        var deleted = 0
        for (name in vol.list(bucketDir)) {
            // Clean up stale temp files from the double-.enc bug (TMP_SUFFIX was ".enc.tmp",
            // producing "<id>.enc.enc.tmp"). These are definitively orphaned — never produced
            // again after the TMP_SUFFIX fix. Strict digit ID + bucket cross-check.
            // Current-format .enc.tmp files are intentionally NOT purged here (overwritten on
            // next sync for that id; also cleaned by StorageManagementViewModel.resetStorage).
            if (name.endsWith(".enc.enc.tmp")) {
                val legacyIdStr = name.dropLast(12)
                if (legacyIdStr.isNotEmpty() && legacyIdStr.all { it in '0'..'9' }) {
                    val legacyId = legacyIdStr.toLongOrNull()
                    if (legacyId != null && legacyId / 1000 == bucket && vol.delete("$bucketDir/$name")) deleted++
                }
                continue
            }
            // Strict final-media filename "<id>.enc"; tmp files, double-suffixed names, and
            // anything else are skipped (not deleted).
            val id = parseFinalMediaName(name) ?: continue
            // Cross-check the file actually belongs in this bucket per MediaPaths' rule.
            if (id / 1000 != bucket) continue
            val rel = "$bucketDir/$name"
            if (isLegacyOrphan(vol, rel)) {
                if (vol.delete(rel)) deleted++
            }
        }
        return deleted
    }

    /**
     * Returns the numeric id iff [name] is exactly "<id>.enc" where "<id>" is a non-empty,
     * non-negative, pure-ASCII-decimal long. Anything else returns null and is left untouched:
     *  - the double-suffixed partial `*.enc.enc.tmp` (ends with `.tmp`, not `.enc`),
     *  - `*.enc.enc` and other dot-bearing names (non-digit chars),
     *  - signed/whitespace/unicode-digit forms (only `'0'..'9'` accepted),
     *  - non-`.enc` files.
     *
     * Mirrors the literal ".enc" suffix appended by [MediaPaths.mediaRelPath]
     * (`"${id / 1000}/${id}.enc"`); that suffix is not exposed as a const on MediaPaths.
     */
    private fun parseFinalMediaName(name: String): Long? {
        if (!name.endsWith(".enc")) return null
        val idStr = name.dropLast(4) // strip ".enc"
        if (idStr.isEmpty() || idStr.any { it !in '0'..'9' }) return null
        return idStr.toLongOrNull()?.takeIf { it >= 0 }
    }

    /**
     * Purge orphaned thumbnails only.
     *
     * Thumbnails use the GCM envelope that is **unchanged between v1 and v2**, so unlike media
     * files they carry no magic that distinguishes generations. Deleting unconditionally would
     * therefore also destroy thumbnails belonging to live v2 media — normally masked by the
     * marker, but the marker write is best-effort, so a failed marker would make a later re-scan
     * wipe valid thumbnails until the next rebuild.
     *
     * Rule: delete a thumbnail only when **no v2 media file exists for its id**. That keeps
     * thumbnails of live v2 media while still reclaiming true orphans, and makes a repeated
     * scan idempotent (legacy media already gone, v2 media and its thumbs preserved).
     * Names that are not exactly `<id>.enc` are skipped, mirroring [purgeBucket].
     */
    private suspend fun purgeThumbs(vol: MediaVolume): Int {
        var deleted = 0
        for (name in vol.list("thumbs")) {
            val id = parseFinalMediaName(name) ?: continue
            // Keep thumbnails whose media still exists as v2; only sweep true orphans.
            val mediaRel = MediaPaths.mediaRelPath(id)
            val mediaAlive = try {
                vol.exists(mediaRel) && !isLegacyOrphan(vol, mediaRel)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unknown state — fail closed and preserve the thumbnail.
                true
            }
            if (mediaAlive) continue
            if (vol.delete("thumbs/$name")) deleted++
        }
        return deleted
    }

    /**
     * True ONLY when the file at [relPath] was opened and a full 4-byte header was read and
     * that header is NOT the v2 "SPM2" magic (i.e. confirmed legacy v1 / readable-but-non-v2).
     *
     * Returns false — meaning "do NOT delete, preserve" — in every other case, deliberately
     * fail-closed so a temporarily-unreadable v2 file is never purged:
     *  - any exception while opening/reading (SAF permission flap, transient IO, file gone),
     *  - a short read (file smaller than the magic length, e.g. 0-3 bytes or truncated).
     *
     * Only an *explicitly confirmed* non-v2 header (full read + mismatch) authorizes deletion.
     */
    private suspend fun isLegacyOrphan(vol: MediaVolume, relPath: String): Boolean = try {
        vol.openRead(relPath).use { h ->
            val b = ByteArray(CryptoScheme.MAGIC.size)
            val n = h.read(b, 0, b.size)
            if (n != b.size) return@use false // short read → unknown → preserve
            !b.contentEquals(CryptoScheme.MAGIC)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
