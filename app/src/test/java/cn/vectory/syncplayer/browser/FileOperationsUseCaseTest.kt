package cn.vectory.syncplayer.browser

import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncRelationWithSource
import cn.vectory.syncplayer.data.repository.PlaybackRepository
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.VideoRepository
import cn.vectory.syncplayer.crypto.CryptoScheme
import cn.vectory.syncplayer.crypto.RangeDecryptor
import cn.vectory.syncplayer.storage.volume.SeekableReadHandle
import cn.vectory.syncplayer.storage.volume.EncryptedMediaStore
import cn.vectory.syncplayer.sync.SyncErrorCode
import cn.vectory.syncplayer.sync.SyncException
import cn.vectory.syncplayer.sync.SyncOperation
import cn.vectory.syncplayer.sync.SyncEngine
import dagger.Lazy
import androidx.core.content.FileProvider
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * Focused regression tests for [FileOperationsUseCase] CancellationException handling.
 *
 * The 3rd-round review (FAIL 7.6) flagged that the generic `catch (e: Exception)` blocks
 * scattered through [FileOperationsUseCase.deleteMediaByIdLocked] / [toggleTrashedChecked]
 * would swallow a [CancellationException] (it extends [Exception] in Kotlin coroutines),
 * silently breaking structured-concurrency cancellation. These tests pin each catch site
 * by forcing the underlying call to throw [CancellationException] and asserting that it
 * propagates verbatim instead of being masked as a [DeleteMediaResult.Failed] /
 * [ToggleTrashResult.Failed].
 *
 * Per project testing convention: assert on result types and coroutine propagation only —
 * never on user-facing message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationsUseCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val encryptedMediaStore = mockk<EncryptedMediaStore>(relaxed = true)
    private val encryptedMediaStoreLazy = mockk<Lazy<EncryptedMediaStore>>()
    private val syncEngine = mockk<SyncEngine>(relaxed = true)

    private lateinit var useCase: FileOperationsUseCase
    private lateinit var context: android.content.Context
    private lateinit var cacheRoot: File

    @Before
    fun setup() {
        // SyncEngine exposes the storageOperationMutex as a public val; provide a real Mutex so
        // deleteMediaById can acquire it.
        every { syncEngine.storageOperationMutex } returns kotlinx.coroutines.sync.Mutex()
        every { encryptedMediaStoreLazy.get() } returns encryptedMediaStore
        every { encryptedMediaStore.volumeId } returns "vol-active"
        cacheRoot = tmp.newFolder("cache")
        context = mockk(relaxed = true)
        // ShareTempFiles resolves cacheDir on every call; pin it to an isolated temp dir so
        // buildShareIntent tests have a real filesystem under cacheDir/share.
        every { context.cacheDir } returns cacheRoot
        // FileProvider.getUriForFile parses provider meta-data via PackageManager (verified
        // against androidx.core 1.16.0 bytecode: resolveContentProvider → loadXmlMetaData).
        // Under a relaxed mock this must fail FAST and deterministically: resolveContentProvider
        // → null makes the real FileProvider throw IllegalArgumentException ("missing
        // …FILE_PROVIDER_PATHS meta-data"), which buildShareIntent's catch maps to null. A
        // relaxed ProviderInfo would yield a relaxed XmlResourceParser whose next() returns 0
        // forever — an infinite parse loop.
        val packageManager = mockk<android.content.pm.PackageManager>()
        every { context.getPackageManager() } returns packageManager
        every { packageManager.resolveContentProvider(any<String>(), any<Int>()) } returns null
        useCase = FileOperationsUseCase(
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncRelationRepository = syncRelationRepository,
            encryptedMediaStoreLazy = encryptedMediaStoreLazy,
            syncEngine = syncEngine,
            context = context
        )
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private fun videoEntity(id: Long, volumeId: String = "vol-active") = VideoItemEntity(
        id = id,
        relativePath = "/base/test.mp4",
        fileName = "test.mp4",
        fileSize = 1000L,
        volumeId = volumeId
    )

    private fun matchingBasePaths(): List<SyncRelationWithSource> = listOf(
        SyncRelationWithSource(
            relation = SyncRelation(
                id = 1L,
                name = "rel",
                syncSourceId = 1L,
                remotePath = "/remote",
                localBasePath = "/base"
            ),
            sourceName = "src",
            sourceProtocol = SyncProtocol.SMB
        )
    )

    /**
     * Runs [block] in a real coroutine (not runTest virtual time — the storage mutex uses real
     * suspending) under a [supervisorScope] so a propagated [CancellationException] can be caught
     * at the child boundary without tearing down the test runner.
     *
     * Returns true iff a [CancellationException] propagated out of [block].
     */
    private fun propagatesCancellation(block: suspend () -> Unit): Boolean {
        var propagated = false
        runBlocking {
            supervisorScope {
                val child = launch {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        propagated = true
                        throw e // re-throw to surface on the child job
                    }
                }
                child.join()
            }
        }
        return propagated
    }

    // ===================================================================================
    // Tests — toggleTrashedChecked CancellationException propagation
    // ===================================================================================

    @Test
    fun `toggleTrashedChecked propagates CancellationException instead of returning Failed`() {
        val id = 1L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { playbackRepository.toggleTrashed(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation {
            useCase.toggleTrashedChecked(id, matchingBasePaths())
        }

        assertTrue("CancellationException must propagate, not be swallowed", propagated)
    }

    @Test
    fun `toggleTrashedChecked returns NotAllowed when video is missing`() = runTest {
        val id = 2L
        coEvery { videoRepository.getById(id) } returns null

        val result = useCase.toggleTrashedChecked(id, matchingBasePaths())

        assertEquals(ToggleTrashResult.NotAllowed, result)
    }

    @Test
    fun `toggleTrashedChecked returns NotAllowed when path is outside base dirs`() = runTest {
        val id = 4L
        // Entity path under a non-base dir.
        coEvery { videoRepository.getById(id) } returns VideoItemEntity(
            id = id,
            relativePath = "/other/test.mp4",
            fileName = "test.mp4",
            fileSize = 1000L,
            volumeId = "vol-active"
        )

        val result = useCase.toggleTrashedChecked(id, matchingBasePaths())

        assertEquals(ToggleTrashResult.NotAllowed, result)
    }

    @Test
    fun `toggleTrashedChecked returns Success on a normal toggle`() = runTest {
        val id = 3L
        val basePaths = matchingBasePaths()
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { playbackRepository.toggleTrashed(id) } returns Unit

        val result = useCase.toggleTrashedChecked(id, basePaths)

        assertEquals(ToggleTrashResult.Success, result)
    }

    // ===================================================================================
    // Tests — deleteMediaByIdLocked CancellationException propagation at each catch site
    // ===================================================================================

    @Test
    fun `deleteMediaById propagates CancellationException when cipherMissing exists() throws`() {
        val id = 10L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from exists() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when delete() throws`() {
        val id = 11L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } returns true
        coEvery { encryptedMediaStore.delete(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from delete() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when stillThere exists() throws`() {
        val id = 12L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        // delete returns false → falls into the stillThere re-check branch
        coEvery { encryptedMediaStore.delete(id) } returns false
        // First exists() call (cipherMissing check) returns true; second call (stillThere
        // re-check) throws CancellationException.
        var existsCallCount = 0
        coEvery { encryptedMediaStore.exists(id) } coAnswers {
            existsCallCount++
            if (existsCallCount == 1) true else throw CancellationException("cancel")
        }

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from stillThere exists() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when deleteThumb() throws`() {
        val id = 13L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } returns true
        coEvery { encryptedMediaStore.delete(id) } returns true
        coEvery { encryptedMediaStore.deleteThumb(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from deleteThumb() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when thumbExists re-check throws`() {
        val id = 14L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } returns true
        coEvery { encryptedMediaStore.delete(id) } returns true
        coEvery { encryptedMediaStore.deleteThumb(id) } returns false
        coEvery { encryptedMediaStore.thumbExists(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from thumbExists re-check must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException when deleteMediaRecord throws`() {
        val id = 15L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } returns true
        coEvery { encryptedMediaStore.delete(id) } returns true
        coEvery { encryptedMediaStore.deleteThumb(id) } returns true
        coEvery { videoRepository.deleteMediaRecord(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue("CancellationException from deleteMediaRecord() must propagate", propagated)
    }

    @Test
    fun `deleteMediaById propagates CancellationException in deleteOtherVolumeIndex path`() {
        val id = 16L
        // Volume mismatch routes through deleteOtherVolumeIndex → deleteMediaRecord.
        coEvery { videoRepository.getById(id) } returns videoEntity(id, volumeId = "vol-other")
        coEvery { videoRepository.deleteMediaRecord(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.deleteMediaById(id) }

        assertTrue(
            "CancellationException from deleteMediaRecord in other-volume path must propagate",
            propagated
        )
    }

    // ===================================================================================
    // Tests — normal (non-cancellation) behavior still works
    // ===================================================================================

    @Test
    fun `deleteMediaById returns Failed for non-cancellation exception in exists`() = runBlocking {
        val id = 20L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } throws java.io.IOException("real io error")

        val result = useCase.deleteMediaById(id)

        assertTrue("Non-cancellation exception must surface as Failed", result is DeleteMediaResult.Failed)
        assertFalse("Result must NOT propagate as Success/Skipped", result is DeleteMediaResult.Success)
    }

    @Test
    fun `deleteMediaById returns Success on a clean delete`() = runBlocking {
        val id = 21L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)
        coEvery { encryptedMediaStore.exists(id) } returns true
        coEvery { encryptedMediaStore.delete(id) } returns true
        coEvery { encryptedMediaStore.deleteThumb(id) } returns true
        coEvery { videoRepository.deleteMediaRecord(id) } returns Unit

        val result = useCase.deleteMediaById(id)

        assertEquals(DeleteMediaResult.Success(id), result)
    }

    // ===================================================================================
    // Tests — outer public launch-based methods propagate CancellationException (FAIL 8.7)
    // ===================================================================================
    //
    // The outer fire-and-forget methods (deleteFile / deleteFolder / etc.) wrap their body in
    // `scope.launch { try { … } catch (e: Exception) { errorFlow = … } }`. A bare `catch (Exception)`
    // swallows CancellationException (breaks structured concurrency). These tests force the
    // underlying call to throw CancellationException and assert that the launch block rethrows it
    // (does NOT write errorFlow and does NOT invoke onSuccess) — i.e. the child coroutine is
    // cancelled, not turned into a silent failure.

    /** Joins all child jobs of [scope] so the fire-and-forget launch completes before assertions. */
    private suspend fun joinChildren(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]!!
        // Children list is lazy; iterate to completion.
        job.children.toList().forEach { it.join() }
    }

    @Test
    fun `deleteFile propagates CancellationException without writing errorFlow or calling onSuccess`() = runBlocking {
        val id = 30L
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // deleteFile → withContext(IO){ mutex.withLock{ deleteMediaByIdLocked(id) } }
        // deleteMediaByIdLocked → videoRepository.getById(id) → throws CancellationException.
        coEvery { videoRepository.getById(id) } throws CancellationException("cancel")

        useCase.deleteFile(id, scope, errorFlow) { onSuccessCalled = true }
        joinChildren(scope)

        // CancellationException must propagate: no error written, no success callback.
        assertNull("errorFlow must not be written on cancellation", errorFlow.value)
        assertFalse("onSuccess must not run on cancellation", onSuccessCalled)
        scope.cancel()
    }

    @Test
    fun `deleteFolder propagates CancellationException without writing errorFlow or calling onSuccess`() = runBlocking {
        val path = "/otherfolder"
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // Pass the guards: not a base dir, no descendant base, path not in basePaths (so
        // isFileInBaseDir returns false). Then deleteFolderContentsLocked → getByPathPrefix throws.
        coEvery { syncRelationRepository.getByLocalBasePath(path) } returns null
        coEvery { syncRelationRepository.getAllBasePaths() } returns emptyList()
        coEvery { videoRepository.getByPathPrefix(path) } throws CancellationException("cancel")

        useCase.deleteFolder(path, matchingBasePaths(), scope, errorFlow) { onSuccessCalled = true }
        joinChildren(scope)

        assertNull("errorFlow must not be written on cancellation", errorFlow.value)
        assertFalse("onSuccess must not run on cancellation", onSuccessCalled)
        scope.cancel()
    }

    @Test
    fun `deleteFile writes errorFlow for a NON-cancellation exception`() = runBlocking {
        // Regression guard: only CancellationException is rethrown; real exceptions still surface.
        val id = 31L
        val errorFlow = MutableStateFlow<String?>(null)
        var onSuccessCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        coEvery { videoRepository.getById(id) } throws java.io.IOException("real io")

        useCase.deleteFile(id, scope, errorFlow) { onSuccessCalled = true }
        joinChildren(scope)

        assertNotNull("non-cancellation exception must surface to errorFlow", errorFlow.value)
        assertFalse(onSuccessCalled)
        scope.cancel()
    }

    // ===================================================================================
    // Tests — buildShareIntent (FileProvider temp-file path)
    // ===================================================================================
    //
    // Coverage focus: the security-critical branches of buildShareIntent — missing entity,
    // fail-closed volume ownership, iv length guard, and the red-line #3 CancellationException
    // propagation through every catch site. The happy path (decrypt + FileProvider.getUriForFile)
    // is intentionally not unit-tested here: FileProvider needs a real Android Context (resource
    // lookup for @xml/file_paths) which is unavailable under plain JUnit4. It is verified on
    // device via the buildShareIntent UI flow.

    /** Entity with a valid 8-byte iv (the default `videoEntity` helper leaves iv empty). */
    private fun videoEntityWithIv(
        id: Long,
        fileSize: Long = 1000L,
        volumeId: String = "vol-active",
        fileName: String = "movie.mp4",
    ): VideoItemEntity = VideoItemEntity(
        id = id,
        relativePath = "/base/$fileName",
        fileName = fileName,
        fileSize = fileSize,
        volumeId = volumeId,
        iv = CryptoScheme.newIv(),
    )

    @Test
    fun `buildShareIntent returns null when video is missing`() = runTest {
        val id = 100L
        coEvery { videoRepository.getById(id) } returns null

        assertNull(useCase.buildShareIntent(id))
    }

    @Test
    fun `buildShareIntent returns null on volume mismatch (fail-closed)`() = runTest {
        // Red line #2: a record on a non-active volume must NEVER drive ciphertext decryption
        // on the active store. Pinned by returning null (not throwing, not decrypting).
        val id = 101L
        coEvery { videoRepository.getById(id) } returns videoEntityWithIv(id, volumeId = "vol-other")

        assertNull(useCase.buildShareIntent(id))
    }

    @Test
    fun `buildShareIntent returns null when iv length is wrong`() = runTest {
        // videoEntity() leaves iv = byteArrayOf() (size 0), failing the IV_LEN==8 guard.
        val id = 102L
        coEvery { videoRepository.getById(id) } returns videoEntity(id)

        assertNull(useCase.buildShareIntent(id))
    }

    @Test
    fun `buildShareIntent returns null on non-cancellation volumeId lookup failure`() = runBlocking {
        val id = 103L
        coEvery { videoRepository.getById(id) } returns videoEntityWithIv(id)
        every { encryptedMediaStore.volumeId } throws java.io.IOException("volume resolve fail")

        assertNull(useCase.buildShareIntent(id))
    }

    @Test
    fun `buildShareIntent propagates CancellationException when volumeId lookup cancels`() {
        // Red line #3: CancellationException from volumeId lookup must propagate, NOT be swallowed
        // into `return null`. Otherwise scope cancellation would silently complete the share flow.
        val id = 104L
        coEvery { videoRepository.getById(id) } returns videoEntityWithIv(id)
        every { encryptedMediaStore.volumeId } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.buildShareIntent(id) }
        assertTrue("CancellationException from volumeId must propagate", propagated)
    }

    @Test
    fun `buildShareIntent propagates CancellationException when openCiphertextRead cancels`() {
        val id = 105L
        coEvery { videoRepository.getById(id) } returns videoEntityWithIv(id)
        coEvery { encryptedMediaStore.openCiphertextRead(id) } throws CancellationException("cancel")

        val propagated = propagatesCancellation { useCase.buildShareIntent(id) }
        assertTrue("CancellationException from openCiphertextRead must propagate", propagated)
    }

    @Test
    fun `buildShareIntent returns null when openCiphertextRead throws IOException`() = runBlocking {
        val id = 106L
        coEvery { videoRepository.getById(id) } returns videoEntityWithIv(id)
        coEvery { encryptedMediaStore.openCiphertextRead(id) } throws java.io.IOException("io fail")

        assertNull(useCase.buildShareIntent(id))
    }

    @Test
    fun `buildShareIntent returns null and deletes partial temp file when ciphertext is truncated`() = runBlocking {
        // Red line #5: random-read / decryption failure MUST throw (never return dirty plaintext).
        // Here we build a real ciphertext, truncate it, and assert decryption fails AND the
        // partially-written temp file is deleted so a half-written file can never reach FileProvider.
        val id = 107L
        val plain = ByteArray(100_000) { (it and 0xFF).toByte() } // ~2 chunks (64 KiB + tail)
        val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val contentKey = CryptoScheme.deriveContentKey(master)
        val iv = CryptoScheme.newIv()

        // Build a real v2 ciphertext, then drop the last 50 bytes (truncates the final chunk's GCM tag).
        val cipherBuf = ByteArrayOutputStream()
        CryptoScheme.encryptStream(contentKey, iv, plain.size.toLong(), cipherBuf)
            .use { it.write(plain) }
        val truncated = cipherBuf.toByteArray().let { it.copyOfRange(0, it.size - 50) }

        val handle = MemSeekableHandle(truncated)
        coEvery { encryptedMediaStore.openCiphertextRead(id) } returns handle
        every { encryptedMediaStore.contentKey() } returns contentKey

        val video = VideoItemEntity(
            id = id,
            relativePath = "/base/movie.mp4",
            fileName = "movie.mp4",
            fileSize = plain.size.toLong(),
            volumeId = "vol-active",
            iv = iv,
        )
        coEvery { videoRepository.getById(id) } returns video

        val intent = useCase.buildShareIntent(id)

        assertNull("truncated ciphertext must surface as null Intent", intent)
        // BUG-017: the temp file is now named with the video id (movie-107.mp4), so a
        // name-matched filter would silently stop covering it — assert the whole share dir
        // is empty instead (stronger, decoupled from the exact naming scheme).
        val shareDir = File(cacheRoot, "share")
        assertEquals("no leftover partial file after decrypt failure", 0, shareDir.listFiles().orEmpty().size)
    }

    // ===================================================================================
    // Tests — BUG-017: share temp files are collision-free per video id
    // ===================================================================================
    //
    // Same fileName across entities (different dirs, DB-unique by path) used to map to the
    // SAME share temp path — the second share truncated the first recipient's file mid-read.
    // These tests drive the REAL decrypt path (real v2 ciphertext, entity iv == encryption
    // iv, contentKey stubbed to the encrypting key). getUriForFile is stubbed via
    // mockkStatic to capture the file bytes at the LAST moment they are guaranteed to
    // exist, then fail — production now deletes the temp file on that failure (see the
    // cleanup test below), so post-call file assertions would race the cleanup.

    /** Encrypts [plain] with [contentKey] and a fresh iv; returns (iv, ciphertext). */
    private fun encryptFor(contentKey: javax.crypto.SecretKey, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = CryptoScheme.newIv()
        val buf = ByteArrayOutputStream()
        CryptoScheme.encryptStream(contentKey, iv, plain.size.toLong(), buf).use { it.write(plain) }
        return iv to buf.toByteArray()
    }

    /**
     * Stubs [FileProvider.getUriForFile] to capture each handed-over file's name + bytes and
     * then fail. Returns the capture buffer; unmocks in [finally] by the caller.
     */
    private fun stubGetUriForFileCaptureAndFail(): MutableList<Pair<String, ByteArray>> {
        val captured = mutableListOf<Pair<String, ByteArray>>()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } answers {
            val f = thirdArg<File>()
            captured += f.name to f.readBytes()
            throw RuntimeException("uri construction failure")
        }
        return captured
    }

    @Test
    fun `buildShareIntent for two same-named videos writes two distinct temp files`() = runBlocking {
        val plain = ByteArray(70_000) { (it and 0xFF).toByte() } // spans 2 chunks (64 KiB + tail)
        val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val contentKey = CryptoScheme.deriveContentKey(master)
        // Independent ciphertext + iv per entity; entity iv MUST equal the encryption iv
        // (the fixture's random iv would fail GCM auth before any file assertion ran).
        val (ivA, cipherA) = encryptFor(contentKey, plain)
        val (ivB, cipherB) = encryptFor(contentKey, plain)

        coEvery { videoRepository.getById(201L) } returns
            videoEntityWithIv(id = 201L, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = ivA)
        coEvery { videoRepository.getById(202L) } returns
            videoEntityWithIv(id = 202L, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = ivB)
        // Production opens ciphertext by video id (not URI) — dispatch per id, and return a
        // NEW handle per call (MemSeekableHandle holds read position state).
        coEvery { encryptedMediaStore.openCiphertextRead(201L) } answers { MemSeekableHandle(cipherA) }
        coEvery { encryptedMediaStore.openCiphertextRead(202L) } answers { MemSeekableHandle(cipherB) }
        every { encryptedMediaStore.contentKey() } returns contentKey

        val captured = stubGetUriForFileCaptureAndFail()
        try {
            assertNull(useCase.buildShareIntent(201L))
            assertNull(useCase.buildShareIntent(202L))
        } finally {
            unmockkStatic(FileProvider::class)
        }

        // Two same-named entities → two DISTINCT id-suffixed temp files (current code
        // wrote both into one ep1.mp4 — the original RED).
        assertEquals(listOf("ep1-201.mp4", "ep1-202.mp4"), captured.map { it.first })
        // Byte-level equality proves both decryptions ran to completion through the real
        // AEAD v2 path.
        assertArrayEquals(plain, captured[0].second)
        assertArrayEquals(plain, captured[1].second)
    }

    @Test
    fun `second share of the same video does not truncate the first temp file`() = runBlocking {
        // The first share's file is IN USE by a recipient (still on disk). A second share
        // of the SAME video must get its own file — reusing the path would truncate the
        // first recipient's file mid-read (the original BUG-017 truncation).
        // (A share whose URI step FAILED is cleaned up, legitimately freeing its name —
        // that path cannot create the in-use precondition, so it is pre-created here.)
        val firstPlain = ByteArray(70_000) { ((it * 3) and 0xFF).toByte() }
        File(File(cacheRoot, "share").apply { mkdirs() }, "ep1-201.mp4").writeBytes(firstPlain)

        val plain = ByteArray(70_000) { ((it * 7) and 0xFF).toByte() }
        val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val contentKey = CryptoScheme.deriveContentKey(master)
        val (ivA, cipherA) = encryptFor(contentKey, plain)

        coEvery { videoRepository.getById(201L) } returns
            videoEntityWithIv(id = 201L, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = ivA)
        coEvery { encryptedMediaStore.openCiphertextRead(201L) } answers { MemSeekableHandle(cipherA) }
        every { encryptedMediaStore.contentKey() } returns contentKey

        val captured = stubGetUriForFileCaptureAndFail()
        try {
            assertNull(useCase.buildShareIntent(201L))
            assertNull(useCase.buildShareIntent(201L))
        } finally {
            unmockkStatic(FileProvider::class)
        }

        // Both shares avoided the IN-USE base file — each went to its own variant.
        // (Each failed share cleans up its own new file, which legitimately frees that
        // variant name for the next call; distinct -n per call is NOT an invariant
        // under cleanup semantics. The protected file is never re-created/reused.)
        assertEquals(2, captured.size)
        captured.forEach { (name, _) -> assertNotEquals("ep1-201.mp4", name) }
        // Both decryptions completed byte-exact through the real AEAD v2 path.
        assertArrayEquals(plain, captured[0].second)
        assertArrayEquals(plain, captured[1].second)
        // In-use file untouched (content-level, not length-level).
        assertArrayEquals(firstPlain, File(cacheRoot, "share/ep1-201.mp4").readBytes())
    }

    @Test
    fun `share failure after successful decrypt cleans up only the new file`() = runBlocking {
        // An in-use file from an earlier share occupies the first candidate name — the
        // placeholder must survive ANY cleanup this call performs.
        val shareDir = File(cacheRoot, "share").apply { mkdirs() }
        val inUseSentinel = ByteArray(64) { 7 }
        File(shareDir, "ep1-201.mp4").writeBytes(inUseSentinel)

        val plain = "item-201-content".toByteArray()
        val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val contentKey = CryptoScheme.deriveContentKey(master)
        val (iv, cipher) = encryptFor(contentKey, plain)

        coEvery { videoRepository.getById(201L) } returns
            videoEntityWithIv(id = 201L, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = iv)
        coEvery { encryptedMediaStore.openCiphertextRead(201L) } answers { MemSeekableHandle(cipher) }
        every { encryptedMediaStore.contentKey() } returns contentKey

        val captured = stubGetUriForFileCaptureAndFail()
        try {
            // Decrypt SUCCEEDS (byte-exact), then URI construction fails.
            assertNull(useCase.buildShareIntent(201L))
        } finally {
            unmockkStatic(FileProvider::class)
        }

        // The file this call created (the -1 variant; the base name was occupied)…
        assertEquals(listOf("ep1-201-1.mp4"), captured.map { it.first })
        assertArrayEquals(plain, captured.single().second)
        // …is removed once the URI step fails — a decrypted file nothing can consume is garbage.
        assertFalse("new temp file must be cleaned up after URI failure",
            File(shareDir, "ep1-201-1.mp4").exists())
        // …while the in-use placeholder survives byte-exact.
        val kept = File(shareDir, "ep1-201.mp4")
        assertTrue("in-use placeholder must survive", kept.exists())
        assertArrayEquals(inUseSentinel, kept.readBytes())
        assertEquals("only the in-use placeholder may remain",
            listOf("ep1-201.mp4"), shareDir.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `share cancellation while returning to caller deletes only its new file`() = runBlocking {
        val id = 209L
        val shareDir = File(cacheRoot, "share").apply { mkdirs() }
        val sentinelBytes = ByteArray(32) { 9 }
        File(shareDir, "ep1-209.mp4").writeBytes(sentinelBytes)

        val plain = "cancel-after-io".toByteArray()
        val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val contentKey = CryptoScheme.deriveContentKey(master)
        val (iv, cipher) = encryptFor(contentKey, plain)
        coEvery { videoRepository.getById(id) } returns
            videoEntityWithIv(id = id, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = iv)
        coEvery { encryptedMediaStore.openCiphertextRead(id) } returns MemSeekableHandle(cipher)
        every { encryptedMediaStore.contentKey() } returns contentKey

        val cancel = CancellationException("cancel while resuming caller")
        val callerDispatcher = CancelOnResumeDispatcher(cancel)
        var thrown: Throwable? = null
        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(any(), any(), any()) } answers {
                callerDispatcher.cancelOnNextResume = true
                mockk<Uri>(relaxed = true)
            }
            val scope = CoroutineScope(callerDispatcher + SupervisorJob())
            val job = scope.launch {
                try {
                    useCase.buildShareIntent(id)
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            job.join()
            scope.cancel()
        } finally {
            unmockkStatic(FileProvider::class)
        }

        assertTrue("caller-resume cancellation must propagate", thrown is CancellationException)
        assertEquals(listOf("ep1-209.mp4"), shareDir.listFiles().orEmpty().map { it.name })
        assertArrayEquals(sentinelBytes, File(shareDir, "ep1-209.mp4").readBytes())
    }

    @Test
    fun `close CancellationException propagates by identity and cleans the new file`() = runBlocking {
        val id = 210L
        val plain = "close-cancel".toByteArray()
        val master = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val contentKey = CryptoScheme.deriveContentKey(master)
        val (iv, cipher) = encryptFor(contentKey, plain)
        val closeCancel = CancellationException("handle close cancelled")
        coEvery { videoRepository.getById(id) } returns
            videoEntityWithIv(id = id, fileName = "close.mp4", fileSize = plain.size.toLong()).copy(iv = iv)
        coEvery { encryptedMediaStore.openCiphertextRead(id) } returns
            MemSeekableHandle(cipher, closeFailure = closeCancel)
        every { encryptedMediaStore.contentKey() } returns contentKey

        val thrown = runCatching { useCase.buildShareIntent(id) }.exceptionOrNull()

        assertSame("close cancellation must not be swallowed", closeCancel, thrown)
        assertEquals("close cancellation must clean the newly created file", 0,
            File(cacheRoot, "share").listFiles().orEmpty().size)
    }

    @Test
    fun `failed decrypt preparation leaves no pre-created temp file behind`() = runBlocking {
        // openCiphertextRead fails BEFORE decryptWholeTo's inner cleanup could apply (it is
        // outside decryptWholeTo's inner try). With uniqueShareFile pre-creating the file,
        // only the call-site outer cleanup can remove it — this test pins that.
        val id = 208L
        coEvery { videoRepository.getById(id) } returns videoEntityWithIv(id)
        coEvery { encryptedMediaStore.openCiphertextRead(id) } throws
            SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD)

        assertNull(useCase.buildShareIntent(id))

        assertEquals("no pre-created temp file residue after decrypt prep failure",
            0, File(cacheRoot, "share").list()!!.size)
    }

    /** In-memory SeekableReadHandle backed by a fixed byte array (mirrors RangeDecryptorTest's). */
    private class MemSeekableHandle(
        private val buf: ByteArray,
        private val closeFailure: Throwable? = null,
    ) : SeekableReadHandle {
        private var pos = 0L
        override fun seek(position: Long) { pos = position }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(length.toLong(), (buf.size - pos).coerceAtLeast(0)).toInt()
            if (n <= 0) return -1
            System.arraycopy(buf, pos.toInt(), buffer, offset, n)
            pos += n
            return n
        }
        override fun size(): Long = buf.size.toLong()
        override fun close() { closeFailure?.let { throw it } }
    }

    /** Cancels the caller job immediately before dispatching the withContext continuation. */
    private class CancelOnResumeDispatcher(
        private val cancellation: CancellationException,
    ) : CoroutineDispatcher() {
        var cancelOnNextResume = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (cancelOnNextResume) {
                cancelOnNextResume = false
                context[kotlinx.coroutines.Job]?.cancel(cancellation)
            }
            block.run()
        }
    }

    // ===================================================================================
    // Tests — share chunk-walk loop safety (large-file overflow + no-progress)
    // ===================================================================================
    //
    // drainDecryptor was extracted from decryptWholeTo so these contracts are testable
    // without materializing multi-GiB ciphertext: a fake RangeDecryptor records the
    // (offset, len) requests and aborts, so 2 GiB / 4 GiB remaining lengths cost nothing.

    /** Aborts the walk after the first chunk request — multi-GiB sizes stay O(1). */
    private class StopWalkException : RuntimeException("stop")

    /** Hard bound for the no-progress test: guard-less code spins → sentinel, never a hang. */
    private class SpinSentinelException : RuntimeException("spun")

    @Test
    fun `safeErrorDetail exposes only structured codes and class names`() {
        // SyncException → its error code (a safe enum constant, already user-surfaced).
        assertEquals(
            "STORAGE_ERROR",
            safeErrorDetail(SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DOWNLOAD)),
        )
        // Any other throwable → class simple name only; message content NEVER leaks.
        assertEquals(
            "IllegalStateException",
            safeErrorDetail(IllegalStateException("/storage/emulated/0/DCIM/secret movie.mp4 id=42")),
        )
    }

    @Test
    fun `drainDecryptor requests chunk-bounded lengths for multi-GiB remaining sizes`() {
        // 1 GiB is the control (fits Int); the rest exceed Int.MAX_VALUE — a cast-first
        // computation overflows into a negative request length.
        val sizes = listOf(
            1L shl 30,            // 1 GiB — control, stays CHUNK_SIZE either way
            2L shl 30,            // 2 GiB — Int.MIN_VALUE after naive toInt()
            (2L shl 30) + 1L,     // just past the boundary
            4L shl 30,            // 4 GiB
            8L shl 30,            // 8 GiB
        )
        for (size in sizes) {
            val requests = mutableListOf<Pair<Long, Int>>()
            val decryptor = mockk<RangeDecryptor>()
            every { decryptor.readRange(any(), any(), any()) } answers {
                requests += secondArg<Long>() to thirdArg<Int>()
                throw StopWalkException()
            }

            runCatching {
                useCase.drainDecryptor(decryptor, MemSeekableHandle(ByteArray(0)), ByteArrayOutputStream(), size)
            }

            assertEquals("first chunk request for size $size", listOf(0L to CryptoScheme.CHUNK_SIZE), requests)
        }
    }

    @Test
    fun `drainDecryptor fails fast when a read makes no progress`() {
        val size = CryptoScheme.CHUNK_SIZE * 3L // bytes remain → a zero-byte read is a violation
        val decryptor = mockk<RangeDecryptor>()
        var calls = 0
        every { decryptor.readRange(any(), any(), any()) } answers {
            calls++
            if (calls > 4) throw SpinSentinelException() // without a guard the loop spins — bounded here
            ByteArray(0)
        }

        val thrown = runCatching {
            useCase.drainDecryptor(decryptor, MemSeekableHandle(ByteArray(0)), ByteArrayOutputStream(), size)
        }.exceptionOrNull()

        assertTrue("expected an explicit no-progress failure, got $thrown", thrown is IllegalStateException)
        assertTrue("loop must not spin on zero-length reads (calls=$calls)", calls <= 4)
    }

    @Test
    fun `drainDecryptor writes the exact plaintext for multi-chunk sizes`() {
        val size = CryptoScheme.CHUNK_SIZE * 2L + 100L // 2 full chunks + a 100-byte tail
        val plain = ByteArray(size.toInt()) { (it % 251).toByte() }
        val decryptor = mockk<RangeDecryptor>()
        every { decryptor.readRange(any(), any(), any()) } answers {
            val off = secondArg<Long>()
            val len = thirdArg<Int>()
            plain.copyOfRange(off.toInt(), off.toInt() + len)
        }

        val out = ByteArrayOutputStream()
        val written = useCase.drainDecryptor(decryptor, MemSeekableHandle(ByteArray(0)), out, size)

        assertEquals(size, written)
        assertArrayEquals(plain, out.toByteArray())
    }
}
