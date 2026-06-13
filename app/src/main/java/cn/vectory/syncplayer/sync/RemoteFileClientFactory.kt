package cn.vectory.syncplayer.sync

import android.content.Context
import cn.vectory.syncplayer.data.model.SyncProtocol
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteFileClientFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun create(protocol: SyncProtocol): RemoteFileClient = when (protocol) {
        SyncProtocol.SMB -> SmbFileClient(context)
        SyncProtocol.SFTP -> SftpFileClient(context)
    }
}
