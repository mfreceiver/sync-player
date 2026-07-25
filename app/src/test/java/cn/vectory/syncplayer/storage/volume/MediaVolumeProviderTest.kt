package cn.vectory.syncplayer.storage.volume

import android.content.Context
import android.net.Uri
import cn.vectory.syncplayer.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class MediaVolumeProviderTest {

    private val context = mockk<Context>(relaxed = true)
    private val prefs = mockk<UserPreferencesRepository>()
    private lateinit var filesDir: java.io.File

    @Before
    fun setUp() {
        filesDir = createTempDirectory(prefix = "mvp-test-").toFile()
        every { context.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        unmockkStatic(Uri::class)
    }

    @Test
    fun `null mediaStorageTreeUri returns FileVolume`() = runTest {
        coEvery { prefs.getMediaStorageTreeUri() } returns null

        val volume = MediaVolumeProvider(context, prefs).current()

        assertTrue(
            "expected FileVolume, got ${volume::class.java.name}",
            volume is FileVolume,
        )
    }

    @Test
    fun `non-null mediaStorageTreeUri returns SafVolume`() = runTest {
        val treeUriStr = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        mockkStatic(Uri::class)
        val uri = mockk<Uri>(relaxed = true)
        every { Uri.parse(treeUriStr) } returns uri
        every { uri.normalizeScheme() } returns uri
        every { uri.toString() } returns treeUriStr
        coEvery { prefs.getMediaStorageTreeUri() } returns treeUriStr

        val volume = MediaVolumeProvider(context, prefs).current()

        assertTrue(
            "expected SafVolume, got ${volume::class.java.name}",
            volume is SafVolume,
        )
    }
}
