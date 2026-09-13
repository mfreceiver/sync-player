package cn.vectory.syncplayer.provider

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class MediaContentProviderTest {

    private lateinit var provider: MediaContentProvider

    @Before
    fun setUp() {
        val controller = Robolectric.buildContentProvider(MediaContentProvider::class.java)
        provider = controller.create().get()
    }

    @Test
    fun openFile_rejectsWriteMode() {
        val uri = Uri.parse("content://cn.vectory.syncplayer.media/media/1")
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri, "w")
        }
    }

    @Test
    fun openFile_rejectsReadWriteMode() {
        val uri = Uri.parse("content://cn.vectory.syncplayer.media/media/1")
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri, "rw")
        }
    }

    @Test
    fun openFile_rejectsAppendMode() {
        val uri = Uri.parse("content://cn.vectory.syncplayer.media/media/1")
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri, "wa")
        }
    }
}
