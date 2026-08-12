package com.example.p2paudio.audio

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import com.example.p2paudio.logging.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class AndroidOpusDecoder(
    private val frameListener: (PcmFrame, Long) -> Unit
) {
    private var decoder: MediaCodec? = null
    private var formatKey: String? = null
    private var lastWarningAtMs = 0L
    private val frameAssembler = OpusDecodedFrameAssembler(frameListener)

    fun decode(packet: UdpOpusPacket, arrivalRealtimeMs: Long) {
        ensureDecoder(packet)
        val codec = decoder ?: error("Opus decoder is not configured")
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex < 0) {
            logWarning(
                event = "opus_input_unavailable",
                message = "MediaCodec did not expose an input buffer in time",
                context = mapOf("sequence" to packet.sequence)
            )
            return
        }

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(packet.opusPayload)
        codec.queueInputBuffer(
            inputIndex,
            0,
            packet.opusPayload.size,
            packet.timestampMs * 1_000L,
            0
        )
        frameAssembler.enqueuePacket(packet, arrivalRealtimeMs)
        drain(codec)
    }

    fun close() {
        decoder?.runCatching {
            stop()
            release()
        }
        decoder = null
        formatKey = null
        lastWarningAtMs = 0L
        frameAssembler.reset()
    }

    private fun ensureDecoder(packet: UdpOpusPacket) {
        val nextKey = "${packet.sampleRate}-${packet.channels}"
        if (decoder != null && formatKey == nextKey) {
            return
        }

        close()
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            packet.sampleRate,
            packet.channels
        ).apply {
            setByteBuffer("csd-0", createOpusHeader(packet.sampleRate, packet.channels))
            setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))
            setByteBuffer("csd-2", ByteBuffer.wrap(ByteArray(8)))
        }

        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, 0)
        codec.start()
        decoder = codec
        formatKey = nextKey
        AppLogger.i(
            "OpusDecoder",
            "decoder_ready",
            "Configured MediaCodec Opus decoder",
            context = mapOf(
                "sampleRate" to packet.sampleRate,
                "channels" to packet.channels
            )
        )
    }

    private fun drain(codec: MediaCodec) {
        val bufferInfo = BufferInfo()
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    AppLogger.d(
                        "OpusDecoder",
                        "decoder_output_format_changed",
                        "Decoder output format changed",
                        context = mapOf("format" to codec.outputFormat.toString())
                    )
                }
                else -> {
                    if (outputIndex < 0) {
                        return
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val readableBuffer = outputBuffer.duplicate().apply {
                            position(bufferInfo.offset)
                            limit(bufferInfo.offset + bufferInfo.size)
                        }
                        val pcmBytes = ByteArray(bufferInfo.size)
                        readableBuffer.get(pcmBytes)
                        frameAssembler.appendDecodedPcm(pcmBytes)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun createOpusHeader(sampleRate: Int, channels: Int): ByteBuffer {
        return ByteBuffer.allocate(19)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
put("OpusHead".toByteArray())
                put(1)
                put(channels.toByte())
                putShort(312)
                putInt(sampleRate)
                putShort(0)
                put(0)
                flip()
            }
    }

    private fun logWarning(event: String, message: String, context: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        if (now - lastWarningAtMs < WARNING_LOG_INTERVAL_MS) {
            return
        }
        lastWarningAtMs = now
        AppLogger.w("OpusDecoder", event, message, context)
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 20_000L
        private const val WARNING_LOG_INTERVAL_MS = 1_000L
    }
}

internal data class PendingDecodedPacket(
    val sequence: Int,
    val timestampMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val frameSamplesPerChannel: Int,
    val arrivalRealtimeMs: Long
) {
    val expectedPcmBytes: Int = frameSamplesPerChannel * channels * (bitsPerSample / 8).coerceAtLeast(1)
}

internal class OpusDecodedFrameAssembler(
    private val frameListener: (PcmFrame, Long) -> Unit
) {
    private val pendingPackets = ArrayDeque<PendingDecodedPacket>()
    private val pendingPcmBytes = PcmByteQueue()

    fun enqueuePacket(packet: UdpOpusPacket, arrivalRealtimeMs: Long) {
        pendingPackets.addLast(
            PendingDecodedPacket(
                sequence = packet.sequence,
                timestampMs = packet.timestampMs,
                sampleRate = packet.sampleRate,
                channels = packet.channels,
                bitsPerSample = 16,
                frameSamplesPerChannel = packet.frameSamplesPerChannel,
                arrivalRealtimeMs = arrivalRealtimeMs
            )
        )
        emitCompleteFrames()
    }

    fun appendDecodedPcm(pcmBytes: ByteArray) {
        pendingPcmBytes.append(pcmBytes)
        emitCompleteFrames()
    }

    fun reset() {
        pendingPackets.clear()
        pendingPcmBytes.clear()
    }

    private fun emitCompleteFrames() {
        while (pendingPackets.isNotEmpty()) {
            val nextPacket = pendingPackets.first()
            if (pendingPcmBytes.availableBytes < nextPacket.expectedPcmBytes) {
                return
            }

            val frameBytes = pendingPcmBytes.read(nextPacket.expectedPcmBytes)
            pendingPackets.removeFirst()
            val bytesPerSampleFrame = nextPacket.channels * (nextPacket.bitsPerSample / 8).coerceAtLeast(1)
            check(frameBytes.size % bytesPerSampleFrame == 0) {
                "Decoded PCM byte count ${frameBytes.size} is not aligned to the audio format"
            }
            frameListener(
                PcmFrame(
                    sequence = nextPacket.sequence,
                    timestampMs = nextPacket.timestampMs,
                    sampleRate = nextPacket.sampleRate,
                    channels = nextPacket.channels,
                    bitsPerSample = nextPacket.bitsPerSample,
                    frameSamplesPerChannel = frameBytes.size / bytesPerSampleFrame,
                    pcmBytes = frameBytes
                ),
                nextPacket.arrivalRealtimeMs
            )
        }
    }
}

private class PcmByteQueue {
    private val chunks = ArrayDeque<ByteArray>()
    var availableBytes: Int = 0
        private set

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        chunks.addLast(bytes)
        availableBytes += bytes.size
    }

    fun read(byteCount: Int): ByteArray {
        require(byteCount in 0..availableBytes)
        if (byteCount == 0) {
            return ByteArray(0)
        }

        val output = ByteArray(byteCount)
        var outputOffset = 0
        while (outputOffset < byteCount) {
            val chunk = chunks.removeFirst()
            val copyCount = min(chunk.size, byteCount - outputOffset)
            chunk.copyInto(
                destination = output,
                destinationOffset = outputOffset,
                startIndex = 0,
                endIndex = copyCount
            )
            if (copyCount < chunk.size) {
                chunks.addFirst(chunk.copyOfRange(copyCount, chunk.size))
            }
            outputOffset += copyCount
        }
        availableBytes -= byteCount
        return output
    }

    fun clear() {
        chunks.clear()
        availableBytes = 0
    }
}
