package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import cn.vectory.syncplayer.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteFileClientFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    // AND-090: pass the injected dispatcher through to the clients so their
    // withContext blocks are TestDispatcher-substitutable.
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun create(protocol: SyncProtocol): RemoteFileClient = when (protocol) {
        SyncProtocol.SMB -> SmbFileClient(context, ioDispatcher = ioDispatcher)
        SyncProtocol.SFTP -> SftpFileClient(context, ioDispatcher = ioDispatcher)
    }
}
