package cn.vectory.syncplayer.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import cn.vectory.syncplayer.data.repository.SyncRelationRepository
import cn.vectory.syncplayer.data.repository.SyncSourceRepository
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.RemoteFileClientFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression test for CQ-004:
 * deleteRelation() must contain repository exceptions and set DeleteFailed
 * without escaping to uncaught exception handler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Audit20260913V4DeleteCrashTest {

    private val syncRelationRepository = mockk<SyncRelationRepository>(relaxed = true)
    private val syncSourceRepository = mockk<SyncSourceRepository>(relaxed = true)
    private val remoteFileClientFactory = mockk<RemoteFileClientFactory>(relaxed = true)
    private val storagePaths = mockk<StoragePaths>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.filesDir } returns File("userdata")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(): SyncRelationEditViewModel = SyncRelationEditViewModel(
        syncRelationRepository = syncRelationRepository,
        syncSourceRepository = syncSourceRepository,
        remoteFileClientFactory = remoteFileClientFactory,
        storagePaths = storagePaths,
        context = context,
        ioDispatcher = UnconfinedTestDispatcher(),
        savedStateHandle = SavedStateHandle(mapOf("relationId" to 5L)),
    )

    private fun withUncaughtCapture(block: () -> Unit): List<Throwable> {
        val caught = CopyOnWriteArrayList<Throwable>()
        val originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        val originalCurrent = Thread.currentThread().uncaughtExceptionHandler
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught.add(e) }
        Thread.currentThread().uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            block()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalDefault)
            Thread.currentThread().uncaughtExceptionHandler = originalCurrent
        }
        return caught
    }

    @Test
    fun `repository failure during deleteRelation is contained and does not crash`() {
        coEvery { syncRelationRepository.delete(5L) } throws RuntimeException("sqlite boom")

        val vm = createVm()
        val caught = withUncaughtCapture { vm.deleteRelation() }

        assertTrue(
            "repository exception must be contained (handled as state, no uncaught crash); " +
                "uncaught=${caught.map { it.javaClass.simpleName + ":" + it.message }}",
            caught.isEmpty()
        )
        assertEquals(RelationSaveResult.DeleteFailed, vm.saveResult.value)
    }

    @Test
    fun `control - successful delete sets Deleted result without crash`() {
        val vm = createVm()
        val caught = withUncaughtCapture { vm.deleteRelation() }

        coVerify(exactly = 1) { syncRelationRepository.delete(5L) }
        assertEquals(RelationSaveResult.Deleted, vm.saveResult.value)
        assertTrue(caught.isEmpty())
    }
}
