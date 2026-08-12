package com.example.p2paudio.audio

import com.example.p2paudio.transport.UdpOpusListenerTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpOpusPacketCodecTest {

    @Test
    fun encodeDecodeRoundTrip_preservesPacket() {
        val packet = UdpOpusPacket(
            sequence = 7,
            timestampMs = 1_760_000_987_654,
            sampleRate = 48_000,
            channels = 1,
            frameSamplesPerChannel = 480,
            opusPayload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        )

        val encoded = UdpOpusPacketCodec.encode(packet)
        val decoded = UdpOpusPacketCodec.decode(encoded)

        assertNotNull(decoded)
        decoded!!
        assertEquals(packet.sequence, decoded.sequence)
        assertEquals(packet.timestampMs, decoded.timestampMs)
        assertEquals(packet.sampleRate, decoded.sampleRate)
        assertEquals(packet.channels, decoded.channels)
        assertEquals(packet.frameSamplesPerChannel, decoded.frameSamplesPerChannel)
        assertArrayEquals(packet.opusPayload, decoded.opusPayload)
    }

    @Test
    fun decodeRejectsUnexpectedMagic() {
        val raw = ByteArray(26)
        val decoded = UdpOpusPacketCodec.decode(raw)

        assertNull(decoded)
    }

    @Test
    fun listenerTransportBufferCanHoldMaximumWindowsPacket() {
        assertEquals(
            UdpOpusPacketCodec.HEADER_BYTES + UdpOpusListenerTransport.MAX_OPUS_PAYLOAD_BYTES,
            UdpOpusListenerTransport.MAX_PACKET_BYTES
        )
    }
}
