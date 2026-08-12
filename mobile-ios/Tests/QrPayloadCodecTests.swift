import Foundation
import XCTest
@testable import P2PAudio

final class QrPayloadCodecTests: XCTestCase {
    func testEncodeInitCompressesAndDecodesLargePayload() throws {
        let payload = PairingInitPayload(
            sessionId: "session-1",
            senderDeviceName: "iphone",
            senderPubKeyFingerprint: "fp",
            offerSdp: "v=0\n" + String(repeating: "a=candidate:1 1 UDP 12345 192.168.0.10 5000 typ host\n", count: 120),
            expiresAtUnixMs: 1_760_000_000_000
        )

        let encoded = try QrPayloadCodec.encodeInit(payload)
        XCTAssertTrue(encoded.hasPrefix("p2paudio-z1:"))

        let decoded = try QrPayloadCodec.decodeInit(encoded)
        assertEqualInit(decoded, payload)
    }

    func testEncodeDecodeUdpInitRoundTrip() throws {
        let payload = UdpInitPayload(
            sessionId: "udp-session-1",
            senderDeviceName: "windows",
            expiresAtUnixMs: 1_760_000_000_000
        )

        let encoded = try QrPayloadCodec.encodeUdpInit(payload)
        let decoded = try QrPayloadCodec.decodeUdpInit(encoded)

        XCTAssertEqual(decoded.version, payload.version)
        XCTAssertEqual(decoded.phase, payload.phase)
        XCTAssertEqual(decoded.transport, payload.transport)
        XCTAssertEqual(decoded.sessionId, payload.sessionId)
        XCTAssertEqual(decoded.senderDeviceName, payload.senderDeviceName)
        XCTAssertEqual(decoded.expiresAtUnixMs, payload.expiresAtUnixMs)
    }

    func testEncodeDecodeUdpConfirmRoundTrip() throws {
        let payload = UdpConfirmPayload(
            sessionId: "udp-session-2",
            receiverDeviceName: "iphone",
            receiverPort: 49_152,
            expiresAtUnixMs: 1_760_000_000_123
        )

        let encoded = try QrPayloadCodec.encodeUdpConfirm(payload)
        let decoded = try QrPayloadCodec.decodeUdpConfirm(encoded)

        XCTAssertEqual(decoded.version, payload.version)
        XCTAssertEqual(decoded.phase, payload.phase)
        XCTAssertEqual(decoded.transport, payload.transport)
        XCTAssertEqual(decoded.sessionId, payload.sessionId)
        XCTAssertEqual(decoded.receiverDeviceName, payload.receiverDeviceName)
        XCTAssertEqual(decoded.receiverPort, payload.receiverPort)
        XCTAssertEqual(decoded.expiresAtUnixMs, payload.expiresAtUnixMs)
    }

    func testDecodeInitRejectsLegacyRawJson() {
        let raw = """
        {
          "version":"1",
          "role":"sender",
          "sessionId":"session-legacy",
          "senderDeviceName":"iphone",
          "senderPubKeyFingerprint":"fp",
          "offerSdp":"v=0\\na=fingerprint:sha-256 test\\n",
          "expiresAtUnixMs":1760000000000
        }
        """
        XCTAssertThrowsError(try QrPayloadCodec.decodeInit(raw))
    }

    func testDecodeInitRejectsEmptyCompressedPayload() {
        XCTAssertThrowsError(try QrPayloadCodec.decodeInit("p2paudio-z1:")) { error in
            guard let failure = error as? SessionFailure else {
                return XCTFail("Expected SessionFailure")
            }
            XCTAssertEqual(failure.code, .invalidPayload)
        }
    }

    func testDecodeInitRejectsInvalidCompressedBase64() {
        XCTAssertThrowsError(try QrPayloadCodec.decodeInit("p2paudio-z1:***invalid***")) { error in
            guard let failure = error as? SessionFailure else {
                return XCTFail("Expected SessionFailure")
            }
            XCTAssertEqual(failure.code, .invalidPayload)
        }
    }

    private func assertEqualInit(_ lhs: PairingInitPayload, _ rhs: PairingInitPayload) {
        XCTAssertEqual(lhs.version, rhs.version)
        XCTAssertEqual(lhs.phase, rhs.phase)
        XCTAssertEqual(lhs.sessionId, rhs.sessionId)
        XCTAssertEqual(lhs.senderDeviceName, rhs.senderDeviceName)
        XCTAssertEqual(lhs.senderPubKeyFingerprint, rhs.senderPubKeyFingerprint)
        XCTAssertEqual(lhs.offerSdp, rhs.offerSdp)
        XCTAssertEqual(lhs.expiresAtUnixMs, rhs.expiresAtUnixMs)
    }
}
