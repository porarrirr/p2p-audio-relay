package com.example.p2paudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusDecodedFrameAssemblerTest {

    @Test
    fun appendDecodedPcmWaitsUntilQueuedPacketFrameIsComplete() {
        val emittedFrames = mutableListOf<Pair<PcmFrame, Long>>()
        val assembler = OpusDecodedFrameAssembler { frame, arrivalRealtimeMs ->
            emittedFrames.add(frame to arrivalRealtimeMs)
        }

        assembler.enqueuePacket(
            packet(sequence = 7, timestampMs = 700L, frameSamplesPerChannel = 2_880),
            arrivalRealtimeMs = 1_234L
        )

        assembler.appendDecodedPcm(ByteArray(3_840))
        assembler.appendDecodedPcm(ByteArray(3_840))

        assertTrue(emittedFrames.isEmpty())

        assembler.appendDecodedPcm(ByteArray(3_840))

        assertEquals(1, emittedFrames.size)
        val (frame, arrivalRealtimeMs) = emittedFrames.single()
        assertEquals(7, frame.sequence)
        assertEquals(700L, frame.timestampMs)
        assertEquals(48_000, frame.sampleRate)
        assertEquals(2, frame.channels)
        assertEquals(16, frame.bitsPerSample)
        assertEquals(2_880, frame.frameSamplesPerChannel)
        assertEquals(11_520, frame.pcmBytes.size)
        assertEquals(1_234L, arrivalRealtimeMs)
    }

    @Test
    fun appendDecodedPcmEmitsQueuedPacketsInOrderFromCombinedOutput() {
        val emittedFrames = mutableListOf<Pair<PcmFrame, Long>>()
        val assembler = OpusDecodedFrameAssembler { frame, arrivalRealtimeMs ->
            emittedFrames.add(frame to arrivalRealtimeMs)
        }

        assembler.enqueuePacket(
            packet(sequence = 11, timestampMs = 220L, frameSamplesPerChannel = 960),
            arrivalRealtimeMs = 2_000L
        )
        assembler.enqueuePacket(
            packet(sequence = 12, timestampMs = 240L, frameSamplesPerChannel = 960),
            arrivalRealtimeMs = 2_020L
        )

        assembler.appendDecodedPcm(ByteArray(7_680))

        assertEquals(listOf(11, 12), emittedFrames.map { it.first.sequence })
        assertEquals(listOf(220L, 240L), emittedFrames.map { it.first.timestampMs })
        assertEquals(listOf(960, 960), emittedFrames.map { it.first.frameSamplesPerChannel })
        assertEquals(listOf(2_000L, 2_020L), emittedFrames.map { it.second })
    }

    @Test
    fun resetClearsQueuedPacketsAndBufferedPcm() {
        val emittedFrames = mutableListOf<Pair<PcmFrame, Long>>()
        val assembler = OpusDecodedFrameAssembler { frame, arrivalRealtimeMs ->
            emittedFrames.add(frame to arrivalRealtimeMs)
        }

        assembler.enqueuePacket(
            packet(sequence = 3, timestampMs = 60L, frameSamplesPerChannel = 960),
            arrivalRealtimeMs = 300L
        )
        assembler.appendDecodedPcm(ByteArray(1_920))

        assembler.reset()
        assembler.appendDecodedPcm(ByteArray(3_840))
        assertTrue(emittedFrames.isEmpty())

        assembler.enqueuePacket(
            packet(sequence = 4, timestampMs = 80L, frameSamplesPerChannel = 960),
            arrivalRealtimeMs = 320L
        )
        assembler.appendDecodedPcm(ByteArray(3_840))

        assertEquals(listOf(4), emittedFrames.map { it.first.sequence })
        assertEquals(listOf(320L), emittedFrames.map { it.second })
    }

    private fun packet(
        sequence: Int,
        timestampMs: Long,
        frameSamplesPerChannel: Int
    ): UdpOpusPacket {
        return UdpOpusPacket(
            sequence = sequence,
            timestampMs = timestampMs,
            sampleRate = 48_000,
            channels = 2,
            frameSamplesPerChannel = frameSamplesPerChannel,
            opusPayload = byteArrayOf(1, 2, 3)
        )
    }
}
