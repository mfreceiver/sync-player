package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.data.model.SyncRelation
import cn.vectory.syncplayer.data.model.SyncSource
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * L4-1 contract tests — protocol-aware remote path validation/normalization at the
 * save entry of [SyncRelationEditViewModel].
 *
 * Path policy (SMB vs SFTP are deliberately different):
 *  - SMB strips a leading `/` (share-root-relative model); bare `/` (root) is REJECTED.
 *  - SFTP preserves a leading `/` (absolute anchoring semantics); `/` root is a legal
 *    existing sync target; bare `.` is REJECTED for both protocols (cwd-relative must
 *    not be flipped into an absolute root); `..` segments are REJECTED for both.
 *  - Empty and non-first `.` segments collapse; in-segment dots (`shows/v2.0`) stay.
 *
 * Save-input snapshot: the protocol-relevant fields (sourceId, protocol, raw remote
 * path) are captured ONCE before the first suspension point — a source/path switch
 * during the suspension window must never produce a mismatched
 * "SMB-normalized path + SFTP sourceId" relation.
 *
 * Unknown protocol (selected source not loaded): fail closed — the path is NOT
 * persisted under any default-protocol normalization.
 *
 * Conventions: asserts persisted shapes and call counts, never user-visible text
 * (getString stubs return per-resource constants "res-<id>" so the fetched resource
 * is asserted, not its localized value).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRelationEditViewModelRemotePathTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>(relaxed = true)
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val smbSource = SyncSource(
        id = 1L, name = "smb", protocol = SyncProtocol.SMB,
        host = "h", port = 1, username = "u", password = "p"
    )
    private val sftpSource = SyncSource(
        id = 2L, name = "sftp", protocol = SyncProtocol.SFTP,
        host = "h", port = 2, username = "u", password = "p"
    )

    private lateinit var vm: SyncRelationEditViewModel

    private val invalidRemotePath = "res-${R.string.relation_invalid_remote_path}"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // init loadStorageInfo() must not suspend on the real repo (harness rule ①).
        coEvery { syncSourceRepository.getAll() } returns listOf(smbSource, sftpSource)
        every { storagePaths.normalizeAndValidateRelativePath(any()) } returns "base"
        coEvery { syncRelationRepository.checkBasePathConflict(any(), any()) } returns emptyList()
        // Distinct constant per resource so tests assert WHICH resource was fetched.
        every { context.getString(any()) } answers { "res-${firstArg<Int>()}" }
        every { context.getString(any(), any()) } answers { "res-${firstArg<Int>()}" }
        // Harness rule ②: inject an unconfined ioDispatcher — setMain alone does not
        // make the constructor-injected dispatcher synchronous.
        vm = SyncRelationEditViewModel(
            syncRelationRepository = syncRelationRepository,
            syncSourceRepository = syncSourceRepository,
            remoteFileClientFactory = remoteFileClientFactory,
            storagePaths = storagePaths,
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(),
            savedStateHandle = SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun fillForm(sourceId: Long, remotePath: String) {
        vm.setName("rel")
        vm.selectSource(sourceId)
        vm.setRemotePath(remotePath)
        vm.setLocalBasePath("movies")
    }

    @Test
    fun `smb root path is rejected and never persisted`() = runTest {
        fillForm(sourceId = 1L, remotePath = "/")
        vm.save()

        coVerify(exactly = 0) { syncRelationRepository.save(any()) }
        assertEquals(invalidRemotePath, vm.error.value)
    }

    @Test
    fun `sftp root path is preserved as legal sync target`() = runTest {
        fillForm(sourceId = 2L, remotePath = "/")
        vm.save()

        coVerify { syncRelationRepository.save(match { it.remotePath == "/" }) }
        assertEquals(null, vm.error.value)
    }

    @Test
    fun `empty segments collapse before persisting`() = runTest {
        fillForm(sourceId = 1L, remotePath = "a//b")
        vm.save()

        coVerify { syncRelationRepository.save(match { it.remotePath == "a/b" }) }
        assertEquals(null, vm.error.value)
    }

    @Test
    fun `dot-dot segments are rejected and never persisted`() = runTest {
        fillForm(sourceId = 1L, remotePath = "a/../b")
        vm.save()

        coVerify(exactly = 0) { syncRelationRepository.save(any()) }
        assertEquals(invalidRemotePath, vm.error.value)
    }

    @Test
    fun `unknown protocol source missing fails closed without default normalization`() = runTest {
        // 99L is not in availableSources → protocol unknown. The path must NOT be
        // normalized under an assumed protocol (e.g. SMB) and persisted.
        fillForm(sourceId = 99L, remotePath = "/a/b")
        vm.save()

        coVerify(exactly = 0) { syncRelationRepository.save(any()) }
        assertEquals(invalidRemotePath, vm.error.value)
    }

    @Test
    fun `save uses one snapshot of source and remote path across suspension window`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { syncRelationRepository.checkBasePathConflict(any(), any()) } coAnswers {
            gate.await()
            emptyList()
        }
        fillForm(sourceId = 1L, remotePath = "/media/movies")
        vm.save()

        // Suspension window: user switches to the SFTP source and retypes the path.
        vm.selectSource(2L)
        vm.setRemotePath("/abs")
        gate.complete(Unit)
        advanceUntilIdle()

        val saved = slot<SyncRelation>()
        coVerify { syncRelationRepository.save(capture(saved)) }
        assertEquals(1L, saved.captured.syncSourceId)
        assertEquals("media/movies", saved.captured.remotePath)
        // F09: typed result — assert the type, not user-visible text.
        assertEquals(RelationSaveResult.Saved, vm.saveResult.value)
    }

    /**
     * Pure-function expectation table for [normalizeRemotePath] (characterization of the
     * new API — lands together with the implementation). SMB vs SFTP columns differ only
     * where the anchoring semantics require it.
     */
    @Test
    fun `normalizeRemotePath expectation table`() {
        // input -> (SMB, SFTP)
        val table = listOf(
            "/media/movies" to ("media/movies" to "/media/movies"),
            "media/movies" to ("media/movies" to "media/movies"),
            "./a" to ("a" to "a"),
            "." to (null to null),
            "/" to (null to "/"),
            "shows/v2.0" to ("shows/v2.0" to "shows/v2.0"),
            "a/../b" to (null to null),
            "a//b" to ("a/b" to "a/b"),
            "a/./b" to ("a/b" to "a/b"),
            "" to (null to null),
            "  " to (null to null),
            " /a//b/. " to ("a/b" to "/a/b"),
        )
        for ((input, expected) in table) {
            assertEquals("SMB: \"$input\"", expected.first, normalizeRemotePath(input, SyncProtocol.SMB))
            assertEquals("SFTP: \"$input\"", expected.second, normalizeRemotePath(input, SyncProtocol.SFTP))
        }
    }
}
