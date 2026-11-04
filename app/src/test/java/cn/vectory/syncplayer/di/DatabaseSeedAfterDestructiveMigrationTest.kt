package cn.vectory.syncplayer.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.syncplayer.NoOpTestApp
import cn.vectory.syncplayer.data.local.AppDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-1：破坏性迁移 + storage_dataset 补种顺序。
 *
 * 旧实现的 seed 挂在 onDestructiveMigration/onCreate：Room 2.7 破坏性迁移顺序是
 * dropAllTables → onDestructiveMigration 回调 → createAllTables，回调里 INSERT 的
 * storage_dataset 表尚不存在，旧安装（schema v4 文件库）升级到 v5 必崩。
 *
 * 本测试用**生产 builder 配置**（同名磁盘库 + fallbackToDestructiveMigration(true) +
 * 同一个 [DatabaseModule.DatasetSeedCallback]）打开一个预置的旧版本库（user_version=4、
 * 含旧表与旧数据），验证：(a) 迁移不崩；(b) storage_dataset 已补种且 datasetId 非空；
 * (c) 旧数据被破坏性迁移清空。
 *
 * 限制说明：Robolectric 的 SQLite 是本地真实 sqlitejdbc（非真机 Android SQLite），
 * 且预置的 v4 库只还原 user_version/旧表而非完整历史 schema——但破坏性迁移只看
 * 「版本不一致且无迁移路径」，触发路径与真机升级一致，足以证明 seed 时机（onOpen，
 * 一切建表之后）不会被迁移顺序击穿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class DatabaseSeedAfterDestructiveMigrationTest {

    private lateinit var dbFile: File
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("sync-player.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        // 预置「旧安装」：v4 库，含一张旧表与一行旧数据。
        val old = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        old.execSQL("CREATE TABLE legacy_video (id INTEGER PRIMARY KEY, path TEXT NOT NULL)")
        old.execSQL("INSERT INTO legacy_video(path) VALUES('old/legacy.mp4')")
        old.version = 4
        old.close()

        db = Room.databaseBuilder(context, AppDatabase::class.java, "sync-player.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(DatabaseModule.DatasetSeedCallback())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun destructiveMigrationSeedsDatasetWithoutCrash() = kotlinx.coroutines.test.runTest {
        // 首次查询即触发打开/迁移：旧实现在 onDestructiveMigration 里 INSERT 新表会崩
        //（no such table: storage_dataset）。
        val dataset = db.storageDatasetDao().get()!!
        assertNotNull("storage_dataset must be seeded after destructive migration", dataset)
        assertTrue(dataset.datasetId.isNotBlank())
        assertEquals(1L, dataset.id.toLong())

        // 旧表被破坏性迁移 dropAllTables 清掉。
        val cursor = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='legacy_video'")
        cursor.use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }

        // 重开一次：INSERT OR IGNORE 幂等（不重复行、不换 datasetId）。
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(context, AppDatabase::class.java, "sync-player.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(DatabaseModule.DatasetSeedCallback())
            .allowMainThreadQueries()
            .build()
        assertEquals(dataset.datasetId, db.storageDatasetDao().get()?.datasetId)
    }}
