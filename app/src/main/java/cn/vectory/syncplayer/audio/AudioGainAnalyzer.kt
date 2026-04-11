package cn.vectory.syncplayer.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

private const val TARGET_RMS_DB = -18f
private const val MAX_GAIN_DB = 6f
private const val MIN_GAIN_DB = -6f
private const val ANALYSIS_DURATION_US = 60_000_000L

data class AudioGainResult(
    val rmsDb: Float,
    val peakDb: Float,
    val gainDb: Float
)

@Singleton
class AudioGainAnalyzer @Inject constructor() {

    private companion object { const val TAG = "AudioGainAnalyzer" }

    fun analyze(filePath: String): AudioGainResult? {
        val file = File(filePath)
        if (!file.exists()) return null

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            val audioTrack = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val stats = decodePcmAndMeasure(extractor, format, sampleRate, channelCount)
                ?: return null

            val rmsDb = 20f * log10(stats.rms / 32767f)
            val peakDb = 20f * log10(stats.peak / 32767f)
            val rawGain = TARGET_RMS_DB - rmsDb
            val gainDb = rawGain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

            if (peakDb + gainDb > -1f) {
                AudioGainResult(rmsDb, peakDb, (-1f - peakDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB))
            } else {
                AudioGainResult(rmsDb, peakDb, gainDb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio gain analysis failed for $filePath", e)
            null
        } finally {
            try { extractor.release() } catch (e: Exception) { Log.w(TAG, "Failed to release extractor", e) }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private data class PcmStats(val rms: Float, val peak: Float)

    private fun decodePcmAndMeasure(
        extractor: MediaExtractor,
        format: MediaFormat,
        sampleRate: Int,
        channelCount: Int
    ): PcmStats? {
        val codec = android.media.MediaCodec.createDecoderByType(
            format.getString(MediaFormat.KEY_MIME) ?: return null
        )
        return try {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()

            var sumSquared = 0.0
            var peakSample = 0.0
            var totalSamples = 0L
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buf = codec.getInputBuffer(inputIndex) ?: break
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || extractor.sampleTime >= ANALYSIS_DURATION_US) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val info = android.media.MediaCodec.BufferInfo()
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outputIndex >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        break
                    }
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (outBuf != null && info.size > 0) {
                        val shortBuf = outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                        val samplesToRead = (info.size / 2).coerceAtMost(shortBuf.remaining())
                        for (i in 0 until samplesToRead) {
                            val s = shortBuf.get().toFloat()
                            sumSquared += (s * s).toDouble()
                            val abs = kotlin.math.abs(s).toDouble()
                            if (abs > peakSample) peakSample = abs
                            totalSamples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            if (totalSamples == 0L) return null
            PcmStats(sqrt(sumSquared / totalSamples).toFloat(), peakSample.toFloat())
        } catch (e: Exception) {
            Log.w(TAG, "PCM decode and measure failed", e)
            null
        } finally {
            try { codec.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop codec", e) }
            try { codec.release() } catch (e: Exception) { Log.w(TAG, "Failed to release codec", e) }
        }
    }
}
