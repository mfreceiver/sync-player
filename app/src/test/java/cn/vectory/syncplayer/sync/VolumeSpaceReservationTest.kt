package cn.vectory.syncplayer.sync

import cn.vectory.syncplayer.storage.MediaExistence
import cn.vectory.syncplayer.storage.MediaProbeState
import cn.vectory.syncplayer.storage.TmpProbeState
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部 SD v2（任务包 B）· 按卷空间预约账本：
 * 预约总量不超卖（available - reserved > bytes）、release 归还、
 * 卷不可用 → 结构化 Denied、Long 饱和不溢出。
 */
class VolumeSpaceReservationTest {

    private class StaticSpacePort(var available: Long, var unavailable: Boolean = false) : SyncStoragePort {
        override suspend fun snapshotDefaultVolume() = SyncVolumeTarget.INTERNAL
        override suspend fun resolveRef(id: Long): SyncStorageRef? = null
        override suspend fun defaultWriteVolumeId(): String = VOLUME_ID_INTERNAL
        override suspend fun setDefaultWriteVolume(volumeId: String) = true
        override fun isExternalVolume(volumeId: String) = false
        override fun isVolumeAvailable(volumeId: String) = !unavailable
        override suspend fun listVolumes(): List<SyncVolumeInfo> = emptyList()
        override suspend fun registerExternalVolume(): String? = null
        override suspend fun unregisterExternalVolume(volumeId: String) = false
        override suspend fun newAttempt(id: Long, target: SyncVolumeTarget, expectedLength: Long) =
            SyncWriteAttempt(SyncStorageRef(id, target.volumeId), "a")
        override suspend fun openWriteSink(attempt: SyncWriteAttempt): OutputStream =
            OutputStream.nullOutputStream()
        override suspend fun finalize(attempt: SyncWriteAttempt) = true
        override suspend fun verifyReadable(attempt: SyncWriteAttempt) {}
        override suspend fun cancel(attempt: SyncWriteAttempt) {}
        override suspend fun probePendingTmp(attempt: SyncWriteAttempt): TmpProbeState = TmpProbeState.ABSENT
        override suspend fun deleteFinal(attempt: SyncWriteAttempt) = true
        override suspend fun probeFinalized(id: Long, expectedSize: Long): MediaProbeState = MediaProbeState.VALID
        override suspend fun usableSpace(target: SyncVolumeTarget): SyncSpaceOutcome =
            if (unavailable) SyncSpaceOutcome.Unavailable(SyncErrorCode.STORAGE_UNAVAILABLE)
            else SyncSpaceOutcome.Available(available)
        override suspend fun checkMediaExistence(id: Long) = MediaExistence.NOT_FOUND
        override suspend fun checkThumbExistence(id: Long) = MediaExistence.NOT_FOUND
        override suspend fun deleteMedia(id: Long) = true
        override suspend fun deleteThumb(id: Long) = true
        override suspend fun ownedVolumeIds(): Set<String> = emptySet()
        override suspend fun deleteAllOwned(volumeId: String, datasetId: String?) = 0 to 0
        override suspend fun updateSyncStorageMetadata(
            attempt: SyncWriteAttempt, expectedOldGeneration: String?, fileSize: Long,
            remoteModifiedTime: Long?, dateSynced: Long,
        ): Int = 1
    }

    @Test
    fun `reservation ledger prevents overcommit per volume`() = runTest {
        val port = StaticSpacePort(available = 1000L)
        val reservation = VolumeSpaceReservation()

        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, 400L, port) is VolumeSpaceReservation.Result.Reserved)
        val denied = reservation.tryReserve(VOLUME_ID_INTERNAL, 700L, port)
        assertTrue(denied is VolumeSpaceReservation.Result.Denied)
        assertEquals(SyncErrorCode.INSUFFICIENT_SPACE, (denied as VolumeSpaceReservation.Result.Denied).code)
        assertEquals(1000L, denied.available)

        reservation.release(VOLUME_ID_INTERNAL, 400L)
        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, 700L, port) is VolumeSpaceReservation.Result.Reserved)
    }

    @Test
    fun `unavailable volume denies with structured code`() = runTest {
        val port = StaticSpacePort(available = Long.MAX_VALUE, unavailable = true)
        val reservation = VolumeSpaceReservation()

        val denied = reservation.tryReserve(VOLUME_ID_INTERNAL, 100L, port)
        assertTrue(denied is VolumeSpaceReservation.Result.Denied)
        assertEquals(
            SyncErrorCode.STORAGE_UNAVAILABLE,
            (denied as VolumeSpaceReservation.Result.Denied).code
        )
    }

    @Test
    fun `saturation keeps huge reserves finite`() = runTest {
        val port = StaticSpacePort(available = Long.MAX_VALUE)
        val reservation = VolumeSpaceReservation()

        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, Long.MAX_VALUE / 2, port) is VolumeSpaceReservation.Result.Reserved)
        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, Long.MAX_VALUE / 2, port) is VolumeSpaceReservation.Result.Reserved)
        // 第三次：已预约饱和至 MAX-1，剩余 1 < MAX/2 → Denied（不得溢出回绕成 Reserved）。
        val denied = reservation.tryReserve(VOLUME_ID_INTERNAL, Long.MAX_VALUE / 2, port)
        assertTrue(denied is VolumeSpaceReservation.Result.Denied)
    }

    /**
     * P1-13（sol）：negative remaining（已超卖）与 negative available 都必须 Denied，
     * 旧实现 `remaining in 0 until bytes` 对 remaining<0 反而放行（超卖逃逸）。
     */
    @Test
    fun `negative free or oversold ledger denies instead of escaping`() = runTest {
        // available < 0（文件系统已超卖）→ 任何正额预约 Denied。
        val negPort = StaticSpacePort(available = -1L)
        val negReservation = VolumeSpaceReservation()
        assertTrue(
            negReservation.tryReserve(VOLUME_ID_INTERNAL, 100L, negPort) is VolumeSpaceReservation.Result.Denied
        )

        // remaining = available - alreadyReserved < 0（free 下降后被在册预约超过）→ Denied。
        val port = StaticSpacePort(available = 1000L)
        val reservation = VolumeSpaceReservation()
        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, 400L, port) is VolumeSpaceReservation.Result.Reserved)
        port.available = 100L // free 骤降，剩余 100-400 = -300
        val denied = reservation.tryReserve(VOLUME_ID_INTERNAL, 100L, port)
        assertTrue(denied is VolumeSpaceReservation.Result.Denied)
        assertEquals(SyncErrorCode.INSUFFICIENT_SPACE, (denied as VolumeSpaceReservation.Result.Denied).code)
    }

    /** P1-13：free 下降（并发落盘）后，在册预约必须按新快照重新判定。 */
    @Test
    fun `dropping free space denies new reservations against fresh snapshot`() = runTest {
        val port = StaticSpacePort(available = 1000L)
        val reservation = VolumeSpaceReservation()
        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, 400L, port) is VolumeSpaceReservation.Result.Reserved)

        // 其他 lane 已落盘 700B → available 降到 300，剩 300-400 < 400 → Denied。
        port.available = 300L
        assertTrue(reservation.tryReserve(VOLUME_ID_INTERNAL, 400L, port) is VolumeSpaceReservation.Result.Denied)
    }
}
