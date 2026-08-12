import XCTest
@testable import P2PAudio

final class UdpOpusPacketCodecTests: XCTestCase {
    func testEncodeDecodeRoundTripPreservesPacket() {
        let packet = UdpOpusPacket(
            sequence: 7,
            timestampMs: 1_760_000_987_654,
            sampleRate: 48_000,
            channels: 1,
            frameSamplesPerChannel: 480,
            opusPayload: Data([0x11, 0x22, 0x33, 0x44])
        )

        let encoded = UdpOpusPacketCodec.encode(packet)
        let decoded = UdpOpusPacketCodec.decode(encoded)

        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.sequence, packet.sequence)
        XCTAssertEqual(decoded?.timestampMs, packet.timestampMs)
        XCTAssertEqual(decoded?.sampleRate, packet.sampleRate)
        XCTAssertEqual(decoded?.channels, packet.channels)
        XCTAssertEqual(decoded?.frameSamplesPerChannel, packet.frameSamplesPerChannel)
        XCTAssertEqual(decoded?.opusPayload, packet.opusPayload)
    }

    func testDecodeRejectsUnexpectedMagic() {
        let decoded = UdpOpusPacketCodec.decode(Data(repeating: 0, count: UdpOpusPacketCodec.headerBytes))
        XCTAssertNil(decoded)
    }

    func testListenerTransportBufferMatchesMaximumPacketSize() {
        XCTAssertEqual(
            UdpOpusPacketCodec.headerBytes + UdpOpusListenerTransport.maxOpusPayloadBytes,
            UdpOpusListenerTransport.maxPacketBytes
        )
    }
}
