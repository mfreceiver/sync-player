package cn.vectory.syncplayer.player.preview

/**
 * Minimal port over a native preview resource (production: MediaMetadataRetriever).
 *
 * Contract for [SeekPreviewEngine]:
 * - All methods are BLOCKING and may not respond to interruption. The engine
 *   calls them only from its single worker, never concurrently, and never more
 *   than [open] → [decode]* → [close] per instance.
 * - [close] must be idempotent; the engine guarantees it is invoked exactly
 *   once per created instance, but only *after* any in-flight blocking call
 *   has returned — close is never concurrent with open/decode.
 * - [decode] throwing `kotlinx.coroutines.CancellationException` is treated as
 *   a native cancellation signal and is propagated to the waiting caller
 *   (matches the legacy ephemeral-decode contract).
 *
 * The interface is JVM-testable: unit tests plug latch-gated fakes that
 * genuinely block, proving release-after-unblock exactly-once semantics.
 */
internal interface PreviewDecoder<F> {
    /** Blocking resource acquisition (native `setDataSource` in production). */
    fun open()

    /** Blocking frame extraction; may return null when the source has no frame. */
    fun decode(positionMs: Long): F?

    /** Blocking resource release. Idempotent, exactly-once per instance. */
    fun close()
}
