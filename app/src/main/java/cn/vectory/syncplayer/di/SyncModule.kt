package cn.vectory.syncplayer.di

import android.content.Context
import cn.vectory.syncplayer.sync.smb.SmbFileClient
import cn.vectory.syncplayer.sync.sftp.SftpFileClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSmbClient(
        @ApplicationContext context: Context
    ): SmbFileClient {
        return SmbFileClient(context)
    }

    @Provides
    @Singleton
    fun provideSftpClient(
        @ApplicationContext context: Context
    ): SftpFileClient {
        return SftpFileClient(context)
    }
}
