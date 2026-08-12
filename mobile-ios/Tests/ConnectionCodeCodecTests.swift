import XCTest
@testable import P2PAudio

final class ConnectionCodeCodecTests: XCTestCase {
    func testEncodeDecodeRoundTrip() throws {
        let payload = ConnectionCodePayload(
            host: "192.168.0.10",
            port: 8080,
            token: "token-123",
            expiresAtUnixMs: 1_760_000_000_000
        )

        let encoded = try ConnectionCodeCodec.encode(payload)
        let decoded = try ConnectionCodeCodec.decode(encoded)

        XCTAssertEqual(decoded, payload)
    }

    func testDecodeRejectsInvalidPrefix() {
        XCTAssertThrowsError(try ConnectionCodeCodec.decode("invalid")) { error in
            guard let failure = error as? SessionFailure else {
                return XCTFail("Expected SessionFailure")
            }
            XCTAssertEqual(failure.code, .invalidPayload)
        }
    }

    func testLooksLikeConnectionCodeRecognizesPrefix() {
        XCTAssertTrue(ConnectionCodeCodec.looksLikeConnectionCode("p2paudio-c1:host:80:1:token"))
        XCTAssertFalse(ConnectionCodeCodec.looksLikeConnectionCode("not-a-code"))
    }
}
