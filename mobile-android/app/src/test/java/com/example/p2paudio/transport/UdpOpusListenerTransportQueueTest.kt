package com.example.p2paudio.transport

import com.example.p2paudio.audio.UdpOpusPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.PriorityQueue

class UdpOpusListenerTransportQueueTest {

    @Test
    fun enqueueRealtimeDecodePacketDropsOldestPacketWhenQueueIsFull() {
        val pendingPackets = priorityQueueOf(
            QueuedRealtimeDecodePacket(packet(sequence = 1), arrivalRealtimeMs = 100L),
            QueuedRealtimeDecodePacket(packet(sequence = 2), arrivalRealtimeMs = 120L)
        )

        val result = enqueueRealtimeDecodePacket(
            pendingPackets = pendingPackets,
            packet = packet(sequence = 3),
            arrivalRealtimeMs = 140L,
            maxQueuePackets = 2
        )

        assertEquals(1, result.droppedSequence)
        assertNull(result.duplicateSequence)
        assertEquals(listOf(2, 3), pendingPackets.map { it.packet.sequence }.sorted())
    }

    @Test
    fun enqueueRealtimeDecodePacketIgnoresDuplicateSequences() {
        val pendingPackets = priorityQueueOf(
            QueuedRealtimeDecodePacket(packet(sequence = 9), arrivalRealtimeMs = 900L)
        )

        val result = enqueueRealtimeDecodePacket(
            pendingPackets = pendingPackets,
            packet = packet(sequence = 9),
            arrivalRealtimeMs = 950L,
            maxQueuePackets = 2
        )

        assertNull(result.droppedSequence)
        assertEquals(9, result.duplicateSequence)
        assertEquals(1, pendingPackets.size)
        val retainedPacket = pendingPackets.single()
        assertEquals(9, retainedPacket.packet.sequence)
        assertEquals(900L, retainedPacket.arrivalRealtimeMs)
    }

    @Test
    fun pendingDecodeQueuePollsPacketsInSequenceOrderAfterOutOfOrderArrival() {
        val pendingPackets = priorityQueueOf()

        enqueueRealtimeDecodePacket(
            pendingPackets = pendingPackets,
            packet = packet(sequence = 12),
            arrivalRealtimeMs = 1_200L,
            maxQueuePackets = 4
        )
        enqueueRealtimeDecodePacket(
            pendingPackets = pendingPackets,
            packet = packet(sequence = 10),
            arrivalRealtimeMs = 1_000L,
            maxQueuePackets = 4
        )
        enqueueRealtimeDecodePacket(
            pendingPackets = pendingPackets,
            packet = packet(sequence = 11),
            arrivalRealtimeMs = 1_100L,
            maxQueuePackets = 4
        )

        assertEquals(
            listOf(10, 11, 12),
            buildList {
                while (pendingPackets.isNotEmpty()) {
                    val queuedPacket = pendingPackets.poll() ?: break
                    add(queuedPacket.packet.sequence)
                }
            }
        )
    }

    private fun packet(sequence: Int): UdpOpusPacket {
        return UdpOpusPacket(
            sequence = sequence,
            timestampMs = sequence * 20L,
            sampleRate = 48_000,
            channels = 2,
            frameSamplesPerChannel = 960,
            opusPayload = byteArrayOf(1, 2, 3)
        )
    }

    private fun priorityQueueOf(
        vararg packets: QueuedRealtimeDecodePacket
    ): PriorityQueue<QueuedRealtimeDecodePacket> {
        return PriorityQueue<QueuedRealtimeDecodePacket>(
            compareBy<QueuedRealtimeDecodePacket> { it.packet.sequence }
                .thenBy { it.arrivalRealtimeMs }
        ).apply {
            addAll(packets)
        }
    }
}
