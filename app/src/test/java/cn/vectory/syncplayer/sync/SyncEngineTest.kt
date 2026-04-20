package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.data.local.dao.PendingSyncActionDao
import cn.vectory.syncplayer.data.repository.*
import cn.vectory.syncplayer.storage.StoragePaths
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>()
    private val playbackRepository = mockk<PlaybackRepository>()
    private val syncSourceRepository = mockk<SyncSourceRepository>()
    private val syncRelationRepository = mockk<SyncRelationRepository>()
    private val syncLogRepository = mockk<SyncLogRepository>()
    private val pendingSyncActionDao = mockk<PendingSyncActionDao>()
    private val smbFileClient = mockk<SmbFileClient>()
    private val sftpFileClient = mockk<SftpFileClient>()
    private val storagePaths = mockk<StoragePaths>()
    private val videoMetadataExtractor = mockk<VideoMetadataExtractor>()

    private lateinit var syncEngine: SyncEngine
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        syncEngine = SyncEngine(
            context = context,
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            syncSourceRepository = syncSourceRepository,
            syncRelationRepository = syncRelationRepository,
            syncLogRepository = syncLogRepository,
            pendingSyncActionDao = pendingSyncActionDao,
            smbFileClient = smbFileClient,
            sftpFileClient = sftpFileClient,
            storagePaths = storagePaths,
            videoMetadataExtractor = videoMetadataExtractor
        )
    }

    @Test
    fun `dryRun should emit SCANNING phase`() {
        // This is just a boilerplate to show MockK usage
        // Actual test implementation would go here
    }
}
