package cn.vectory.syncplayer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.dao.VideoItemDao
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class VideoItemDaoHasAnyItemsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VideoItemDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `hasAnyItemsFlow emits false when empty and true when item inserted`() = runBlocking {
        assertFalse(dao.hasAnyItemsFlow().first())

        dao.upsert(
            VideoItemEntity(
                syncRelationId = 1L,
                relativePath = "movies/a.mp4",
                fileName = "a.mp4",
                fileSize = 100L,
                mediaType = "VIDEO"
            )
        )

        assertTrue(dao.hasAnyItemsFlow().first())
    }
}
