package com.example.p2paudio.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object UdpOpusPacketCodec {
    private val MAGIC = byteArrayOf('P'.code.toByte(), '2'.code.toByte(), 'A'.code.toByte(), 'U'.code.toByte())
    private const val VERSION: Byte = 1
    internal const val HEADER_BYTES = 26

    fun encode(packet: UdpOpusPacket): ByteArray {
        require(packet.sampleRate > 0)
        require(packet.channels in 1..2)
        require(packet.frameSamplesPerChannel > 0)
        require(packet.opusPayload.isNotEmpty())
        require(packet.opusPayload.size <= 0xFFFF)

        val buffer = ByteBuffer.allocate(HEADER_BYTES + packet.opusPayload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(VERSION)
        buffer.put(packet.channels.toByte())
        buffer.putShort(packet.frameSamplesPerChannel.toShort())
        buffer.putInt(packet.sampleRate)
        buffer.putInt(packet.sequence)
        buffer.putLong(packet.timestampMs)
        buffer.putShort(packet.opusPayload.size.toShort())
        buffer.put(packet.opusPayload)
        return buffer.array()
    }

    fun decode(raw: ByteArray): UdpOpusPacket? {
        if (raw.size < HEADER_BYTES) {
            return null
        }

        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) {
            return null
        }
        if (buffer.get() != VERSION) {
            return null
        }

        val channels = buffer.get().toInt() and 0xFF
        val frameSamplesPerChannel = buffer.short.toInt() and 0xFFFF
        val sampleRate = buffer.int
        val sequence = buffer.int
        val timestampMs = buffer.long
        val payloadSize = buffer.short.toInt() and 0xFFFF
        if (channels !in 1..2 || frameSamplesPerChannel <= 0 || sampleRate <= 0 || payloadSize <= 0) {
            return null
        }
        if (buffer.remaining() != payloadSize) {
            return null
        }

        val payload = ByteArray(payloadSize)
        buffer.get(payload)
        return UdpOpusPacket(
            sequence = sequence,
            timestampMs = timestampMs,
            sampleRate = sampleRate,
            channels = channels,
            frameSamplesPerChannel = frameSamplesPerChannel,
            opusPayload = payload
        )
    }
}
