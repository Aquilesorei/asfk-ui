package com.aquiles.asfk

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

sealed class ReceiverState {
    object Listening : ReceiverState()                           // listening to silence, waiting for a trigger
    data class Capturing(val elapsedMs: Int) : ReceiverState()    // signal detected, capturing
    data class Decoded(val text: String) : ReceiverState()        // frame decoded successfully
    data class Error(val reason: String) : ReceiverState()        // timeout / decode failure
}

/**
 * Listens to the mic continuously, detects the start of an AFSK transmission by
 * energy threshold (radio-squelch style), captures the signal, and tries decoding
 * it with AfskDecoder as the buffer fills up.
 *
 * Requires the android.permission.RECORD_AUDIO permission (requested at runtime).
 */
class AfskReceiver {

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Listening)
    val state: StateFlow<ReceiverState> = _state

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    // Trigger threshold: ambient noise RMS * margin. Calibrated at startup.
    private var noiseFloor = 0.0
    private val triggerMultiplier = 4.0
    private val maxCaptureMs = 8000
    private val decodeAttemptIntervalMs = 200

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope) {
        stop()
        val minBufSize = AudioRecord.getMinBufferSize(
            AfskProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBufSize, AfskProtocol.SAMPLES_PER_BIT * 4)

        val record = AudioRecord(
            // VOICE_RECOGNITION skips the AGC/noise-suppression/echo-cancel that MIC
            // applies by default — those mangle a steady AFSK tone on most phones.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AfskProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 4
        )
        audioRecord = record
        record.startRecording()

        job = scope.launch(Dispatchers.Default) {
            calibrateNoiseFloor(record, bufSize)
            listenLoop(this, record, bufSize)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioRecord?.let {
            try { it.stop() } catch (e: Exception) { /* already stopped */ }
            it.release()
        }
        audioRecord = null
        _state.value = ReceiverState.Listening
    }

    private suspend fun calibrateNoiseFloor(record: AudioRecord, bufSize: Int) {
        val chunk = ShortArray(bufSize)
        var sumRms = 0.0
        val samplesToCalibrate = 10
        for (i in 0 until samplesToCalibrate) {
            val n = record.read(chunk, 0, chunk.size)
            if (n > 0) sumRms += rms(chunk, n)
        }
        noiseFloor = (sumRms / samplesToCalibrate).coerceAtLeast(50.0)
    }

    private fun rms(chunk: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
        return sqrt(sum / n)
    }

    private suspend fun listenLoop(scope: CoroutineScope, record: AudioRecord, bufSize: Int) {
        val chunk = ShortArray(bufSize)
        // The threshold only fires AFTER the sound has started — without this
        // "pre-roll" (the chunk right before the trigger), the first preamble bits
        // are already lost and sync fails. So we always keep the previous chunk.
        val prevChunk = ShortArray(bufSize)
        var prevLen = 0
        val maxFrameSamples = AfskProtocol.maxFrameSamples() + AfskProtocol.SAMPLE_RATE // 1s margin
        val captureBuffer = ShortArray(maxFrameSamples)

        while (scope.isActive) {
            _state.value = ReceiverState.Listening
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) continue

            if (rms(chunk, n) > noiseFloor * triggerMultiplier) {
                val gotRealResult = captureAndDecode(
                    record, prevChunk, prevLen, chunk, n, captureBuffer, maxFrameSamples
                )
                // An early sync failure likely means we missed the real start of a
                // frame (noise spike) — go back to listening right away so we don't
                // miss the frame in progress. A real result (decoded, bad checksum,
                // timeout) stays displayed for 3s so the user has time to read it.
                if (gotRealResult) delay(3000)
            }

            System.arraycopy(chunk, 0, prevChunk, 0, n)
            prevLen = n
        }
    }

    /** Returns true if a definitive result (worth displaying) was produced. */
    private suspend fun captureAndDecode(
        record: AudioRecord,
        preroll: ShortArray,
        prerollLen: Int,
        firstChunk: ShortArray,
        firstChunkLen: Int,
        captureBuffer: ShortArray,
        maxFrameSamples: Int
    ): Boolean {
        var filled = minOf(prerollLen, maxFrameSamples)
        System.arraycopy(preroll, 0, captureBuffer, 0, filled)
        val firstCopyLen = minOf(firstChunkLen, maxFrameSamples - filled)
        System.arraycopy(firstChunk, 0, captureBuffer, filled, firstCopyLen)
        filled += firstCopyLen

        var lastDecodeAttemptSamples = 0
        val startTimeMs = System.currentTimeMillis()

        while (filled < maxFrameSamples) {
            val elapsedMs = (System.currentTimeMillis() - startTimeMs).toInt()
            _state.value = ReceiverState.Capturing(elapsedMs)
            if (elapsedMs > maxCaptureMs) {
                _state.value = ReceiverState.Error("Timeout: incomplete frame after ${maxCaptureMs}ms")
                return true
            }

            val remaining = maxFrameSamples - filled
            val n = record.read(captureBuffer, filled, minOf(firstChunk.size, remaining))
            if (n > 0) filled += n

            val samplesPerAttempt = AfskProtocol.SAMPLE_RATE / 5 // try roughly every 200ms of new audio
            if (filled - lastDecodeAttemptSamples >= samplesPerAttempt) {
                lastDecodeAttemptSamples = filled
                when (val result = AfskDecoder.tryDecode(captureBuffer, filled)) {
                    is DecodeResult.Success -> {
                        _state.value = ReceiverState.Decoded(result.text)
                        return true
                    }
                    is DecodeResult.Failed -> {
                        val earlyNoSync = result.reason.startsWith("Sync not found")
                        _state.value = ReceiverState.Error(result.reason)
                        return !earlyNoSync
                    }
                    is DecodeResult.Incomplete -> { /* keep capturing */ }
                }
            }
        }
        _state.value = ReceiverState.Error("Buffer full, no valid frame detected")
        return true
    }
}
