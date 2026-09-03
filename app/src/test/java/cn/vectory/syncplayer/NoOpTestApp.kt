package cn.vectory.syncplayer

import android.app.Application

/**
 * No-op [Application] for Robolectric tests that exercise pure Compose layout code.
 *
 * Replaces the real [SyncPlayerApp] via `@Config(application = NoOpTestApp::class)`
 * because the Hilt graph pulls in AndroidKeyStore-backed security-crypto master
 * keys (see DatabaseModule.provideMasterKey), and the AndroidKeyStore provider does
 * not exist under Robolectric. These layout tests need no DI at all.
 */
class NoOpTestApp : Application()
