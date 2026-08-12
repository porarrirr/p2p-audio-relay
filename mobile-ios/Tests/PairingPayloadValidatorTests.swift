import XCTest
@testable import P2PAudio

final class PairingPayloadValidatorTests: XCTestCase {
    func testValidateUdpInitRejectsExpiredPayload() {
        let now: Int64 = 1_760_000_000_000
        let payload = UdpInitPayload(
            sessionId: "udp-session-1",
            senderDeviceName: "windows",
            expiresAtUnixMs: now - 1
        )

        XCTAssertThrowsError(try PairingPayloadValidator.validateUdpInit(payload, nowUnixMs: now)) { error in
            guard let failure = error as? SessionFailure else {
                return XCTFail("Expected SessionFailure")
            }
            XCTAssertEqual(failure.code, .sessionExpired)
        }
    }

    func testValidateUdpConfirmRejectsSessionMismatch() {
        let now: Int64 = 1_760_000_000_000
        let payload = UdpConfirmPayload(
            sessionId: "udp-session-a",
            receiverDeviceName: "iphone",
            receiverPort: 49_152,
            expiresAtUnixMs: now + 60_000
        )

        XCTAssertThrowsError(
            try PairingPayloadValidator.validateUdpConfirm(
                payload,
                expectedSessionId: "udp-session-b",
                nowUnixMs: now
            )
        ) { error in
            guard let failure = error as? SessionFailure else {
                return XCTFail("Expected SessionFailure")
            }
            XCTAssertEqual(failure.code, .invalidPayload)
        }
    }
}
