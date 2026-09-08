package cn.vectory.syncplayer.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F02/T6: v2 → v3 保留数据显式迁移（[AppDatabase.MIGRATION_2_3]）。
 *
 * 场景（T3 批准方案验收）：
 *  - 旧行 lastSyncResult 非空 → lastSyncStatus='UNKNOWN'（不默认成功、不默认失败），
 *    其余列（时间/文案/业务字段）原样保留；
 *  - 旧行 lastSyncResult 为 NULL → 全部终态列保持 NULL = 无记录；
 *  - 错误码/失败数列初始为 NULL。
 */
@RunWith(AndroidJUnit4::class)
class SyncRelationMigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate2To3_preservesRows_andMarksLegacyTextAsUnknown() {
        val dbName = "sync-relation-migration-test-${System.nanoTime()}"

        // v2 schema 建库并插入基线数据：先插 sync_source（sync_relation.syncSourceId
        // 有 FK RESTRICT 引用，缺父行会在启 FK 的环境直接插不进去），再插两条
        // sync_relation：有历史文案 / 从未同步。
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO sync_source " +
                    "(id, name, protocol, host, port, username, encryptedPassword, createdAt) " +
                    "VALUES (1, 'test-src', 'SFTP', 'host.local', 22, 'user', 'enc-fixture', 1699998900000)"
            )
            db.execSQL(
                "INSERT INTO sync_relation " +
                    "(name, syncSourceId, remotePath, localBasePath, lastSyncTime, lastSyncResult, createdAt) " +
                    "VALUES ('legacy', 1, '/remote', '/local', 1700000000000, '成功：下载 2 个，删除 0 个', 1699999000000)"
            )
            db.execSQL(
                "INSERT INTO sync_relation " +
                    "(name, syncSourceId, remotePath, localBasePath, lastSyncTime, lastSyncResult, createdAt) " +
                    "VALUES ('never', 1, '/remote2', '/local2', NULL, NULL, 1699999000000)"
            )
        }

        // 跑显式迁移并按 v3 schema 校验。
        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)
        db.query(
            "SELECT name, lastSyncTime, lastSyncResult, lastSyncStatus, lastSyncErrorCode, lastSyncFailedCount " +
                "FROM sync_relation ORDER BY id"
        ).use { c ->
            // 行 1：历史文案行 → 数据保留 + UNKNOWN
            assertTrue(c.moveToFirst())
            assertEquals("legacy", c.getString(0))
            assertEquals("lastSyncTime preserved", 1700000000000L, c.getLong(1))
            assertEquals("legacy text preserved for forward-fix", "成功：下载 2 个，删除 0 个", c.getString(2))
            assertEquals("legacy text must map to UNKNOWN, never success/failure", "UNKNOWN", c.getString(3))
            assertNull(c.getString(4))
            assertTrue(c.isNull(5))

            // 行 2：从未同步 → 全 NULL = 无记录
            assertTrue(c.moveToNext())
            assertEquals("never", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(5))

            assertFalse(c.moveToNext())
        }
        db.close()
    }
}
