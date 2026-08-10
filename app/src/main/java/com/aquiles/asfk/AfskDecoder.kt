package com.aquiles.asfk

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * AFSK protocol shared with afsk_modem.py (laptop):
 *   - bit 0 = 1200 Hz, bit 1 = 2200 Hz
 *   - 100 baud -> 441 samples/bit @ 44100 Hz
 *   - Frame: preamble(16 alternating bits) + sync(0x7E7E) + length(1 byte) + payload + checksum(1 byte)
 */
object AfskProtocol {
    const val SAMPLE_RATE = 44100
    const val FREQ_0 = 1200.0
    const val FREQ_1 = 2200.0
    const val BAUD = 100
    const val SAMPLES_PER_BIT = SAMPLE_RATE / BAUD
    const val SYNC_WORD = 0x7E7E
    const val MAX_PAYLOAD_BYTES = 64

    val PREAMBLE_BITS: List<Int> = (0 until 16).map { it % 2 }

    fun byteToBits(b: Int): List<Int> = (7 downTo 0).map { (b shr it) and 1 }

    fun bitsToByte(bits: List<Int>): Int = bits.fold(0) { acc, bit -> (acc shl 1) or bit }

    /** Preamble + sync word, in the order expected at the head of a frame. */
    fun expectedHeaderBits(): List<Int> {
        val bits = PREAMBLE_BITS.toMutableList()
        bits += byteToBits((SYNC_WORD shr 8) and 0xFF)
        bits += byteToBits(SYNC_WORD and 0xFF)
        return bits
    }

    /** Max plausible duration of a frame (to size the capture buffer), in samples. */
    fun maxFrameSamples(): Int {
        val headerBits = expectedHeaderBits().size
        val maxBits = headerBits + 8 + (MAX_PAYLOAD_BYTES * 8) + 8
        return maxBits * SAMPLES_PER_BIT
    }
}

/** Result of a decode attempt on an audio buffer. */
sealed class DecodeResult {
    data class Success(val text: String, val offset: Int, val syncErrors: Int) : DecodeResult()
    data class Incomplete(val reason: String) : DecodeResult()   // not enough samples yet
    data class Failed(val reason: String) : DecodeResult()       // invalid sync/checksum
}

object AfskDecoder {

    /** Signal power at a given frequency over a window, via the Goertzel algorithm. */
    private fun goertzelPower(samples: ShortArray, offset: Int, len: Int, freq: Double, sampleRate: Int): Double {
        val k = (0.5 + len * freq / sampleRate).toInt()
        val w = 2.0 * PI * k / len
        val coeff = 2.0 * cos(w)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until len) {
            val s = samples[offset + i] / 32768.0
            q0 = coeff * q1 - q2 + s
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cos(w)
        val imag = q2 * sin(w)
        return real * real + imag * imag
    }

    private fun decodeBit(samples: ShortArray, offset: Int, sampleRate: Int): Int {
        val len = AfskProtocol.SAMPLES_PER_BIT
        val p0 = goertzelPower(samples, offset, len, AfskProtocol.FREQ_0, sampleRate)
        val p1 = goertzelPower(samples, offset, len, AfskProtocol.FREQ_1, sampleRate)
        return if (p1 > p0) 1 else 0
    }

    /**
     * Finds the best starting point for the bits in [samples] by comparing against
     * the expected preamble+sync pattern (fine-step correlation search).
     * Returns (offset, errorCount, headerBitCount) or null if the buffer is too short.
     */
    private fun findSyncOffset(samples: ShortArray, validLen: Int): Triple<Int, Int, Int>? {
        val expected = AfskProtocol.expectedHeaderBits()
        val nBits = expected.size
        val spb = AfskProtocol.SAMPLES_PER_BIT

        val maxSearch = validLen - nBits * spb
        if (maxSearch <= 0) return null

        val step = maxOf(1, spb / 8)
        var bestOffset = -1
        var bestErrors = nBits + 1

        var offset = 0
        while (offset < maxSearch) {
            var errors = 0
            for (i in expected.indices) {
                val start = offset + i * spb
                if (start + spb > validLen) { errors = nBits + 1; break }
                if (decodeBit(samples, start, AfskProtocol.SAMPLE_RATE) != expected[i]) {
                    errors++
                    if (errors >= bestErrors) break
                }
            }
            if (errors < bestErrors) {
                bestErrors = errors
                bestOffset = offset
                if (errors == 0) break
            }
            offset += step
        }

        return if (bestOffset >= 0) Triple(bestOffset, bestErrors, nBits) else null
    }

    /**
     * Attempts to decode a full frame from [samples] (a linearized circular buffer,
     * only the first [validLen] samples are valid).
     */
    fun tryDecode(samples: ShortArray, validLen: Int): DecodeResult {
        val syncResult = findSyncOffset(samples, validLen)
            ?: return DecodeResult.Incomplete("Not enough samples yet to search for sync")

        val (offset, errors, headerBits) = syncResult
        if (errors > headerBits / 4) {
            return DecodeResult.Failed("Sync not found ($errors/$headerBits errors)")
        }

        var pos = offset + headerBits * AfskProtocol.SAMPLES_PER_BIT
        val spb = AfskProtocol.SAMPLES_PER_BIT

        fun readByte(): Int? {
            if (pos + 8 * spb > validLen) return null
            val bits = (0 until 8).map { i ->
                decodeBit(samples, pos + i * spb, AfskProtocol.SAMPLE_RATE)
            }
            pos += 8 * spb
            return AfskProtocol.bitsToByte(bits)
        }

        val length = readByte() ?: return DecodeResult.Incomplete("Length not received yet")
        // length 0 is rejected outright: an empty payload's checksum is always 0, so a
        // garbled decode that happens to misread both bytes as 0 would otherwise pass
        // as a false-positive "empty message".
        if (length !in 1..AfskProtocol.MAX_PAYLOAD_BYTES) {
            return DecodeResult.Failed("Invalid length ($length)")
        }

        val payload = ByteArray(length)
        for (i in 0 until length) {
            val b = readByte() ?: return DecodeResult.Incomplete("Payload not received yet ($i/$length)")
            payload[i] = b.toByte()
        }

        val checksum = readByte() ?: return DecodeResult.Incomplete("Checksum not received yet")
        val expectedChecksum = payload.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) % 256 }
        if (checksum != expectedChecksum) {
            return DecodeResult.Failed("Invalid checksum ($errors sync errors)")
        }

        val text = try {
            String(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            return DecodeResult.Failed("Payload not valid UTF-8: ${e.message}")
        }

        return DecodeResult.Success(text, offset, errors)
    }
}
