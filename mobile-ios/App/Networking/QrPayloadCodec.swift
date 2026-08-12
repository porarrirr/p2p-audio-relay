import Foundation
import Compression

enum QrPayloadCodec {
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()
    private static let compressedPrefix = "p2paudio-z1:"
    private static let minBytesForCompression = 256
    private static let maxDecompressedBytes = 512_000

    static func encodeInit(_ payload: PairingInitPayload) throws -> String {
        let data = try encoder.encode(payload)
        guard let raw = String(data: data, encoding: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.failed_encode_offer"))
        }
        return compressTransportIfBeneficial(raw, data: data)
    }

    static func encodeConfirm(_ payload: PairingConfirmPayload) throws -> String {
        let data = try encoder.encode(payload)
        guard let raw = String(data: data, encoding: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.failed_encode_answer"))
        }
        return compressTransportIfBeneficial(raw, data: data)
    }

    static func decodeInit(_ raw: String) throws -> PairingInitPayload {
        let normalized = try decodeTransportString(raw)
        guard let data = normalized.data(using: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_init_payload"))
        }
        return try decoder.decode(PairingInitPayload.self, from: data)
    }

    static func decodeConfirm(_ raw: String) throws -> PairingConfirmPayload {
        let normalized = try decodeTransportString(raw)
        guard let data = normalized.data(using: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_confirm_payload"))
        }
        return try decoder.decode(PairingConfirmPayload.self, from: data)
    }

    static func encodeUdpInit(_ payload: UdpInitPayload) throws -> String {
        let data = try encoder.encode(payload)
        guard let raw = String(data: data, encoding: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_udp_init_payload"))
        }
        return compressTransportIfBeneficial(raw, data: data)
    }

    static func encodeUdpConfirm(_ payload: UdpConfirmPayload) throws -> String {
        let data = try encoder.encode(payload)
        guard let raw = String(data: data, encoding: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_udp_confirm_payload"))
        }
        return compressTransportIfBeneficial(raw, data: data)
    }

    static func decodeUdpInit(_ raw: String) throws -> UdpInitPayload {
        let normalized = try decodeTransportString(raw)
        guard let data = normalized.data(using: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_udp_init_payload"))
        }
        return try decoder.decode(UdpInitPayload.self, from: data)
    }

    static func decodeUdpConfirm(_ raw: String) throws -> UdpConfirmPayload {
        let normalized = try decodeTransportString(raw)
        guard let data = normalized.data(using: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_udp_confirm_payload"))
        }
        return try decoder.decode(UdpConfirmPayload.self, from: data)
    }

    private static func compressTransportIfBeneficial(_ raw: String, data: Data) -> String {
        if data.count < minBytesForCompression {
            return raw
        }
        guard let compressed = zlibCompress(data) else {
            return raw
        }
        let encoded = makeBase64UrlSafe(compressed.base64EncodedString())
        if encoded.count >= raw.count {
            return raw
        }
        return "\(compressedPrefix)\(encoded)"
    }

    private static func decodeTransportString(_ raw: String) throws -> String {
        guard raw.hasPrefix(compressedPrefix) else {
            return raw
        }
        let encoded = String(raw.dropFirst(compressedPrefix.count))
        guard !encoded.isEmpty else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_payload"))
        }

        let standardBase64 = makeBase64Standard(encoded)
        guard let compressedData = Data(base64Encoded: standardBase64) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_payload"))
        }
        guard let decompressed = zlibDecompress(compressedData, maxOutputBytes: maxDecompressedBytes),
              let normalized = String(data: decompressed, encoding: .utf8) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_payload"))
        }
        return normalized
    }

    private static func makeBase64UrlSafe(_ base64: String) -> String {
        base64
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func makeBase64Standard(_ urlSafe: String) -> String {
        var normalized = urlSafe
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 {
            normalized += String(repeating: "=", count: 4 - remainder)
        }
        return normalized
    }

    private static func zlibCompress(_ data: Data) -> Data? {
        if data.isEmpty {
            return Data()
        }
        let sourceCount = data.count
        let maxOutputSize = max(1024, sourceCount * 4)
        var destinationSize = max(512, sourceCount / 2)

        return data.withUnsafeBytes { sourceBuffer in
            guard let sourceBase = sourceBuffer.bindMemory(to: UInt8.self).baseAddress else {
                return nil
            }

            while destinationSize <= maxOutputSize {
                var destination = Data(count: destinationSize)
                let compressedCount = destination.withUnsafeMutableBytes { destinationBuffer in
                    guard let destinationBase = destinationBuffer.bindMemory(to: UInt8.self).baseAddress else {
                        return 0
                    }
                    return compression_encode_buffer(
                        destinationBase,
                        destinationSize,
                        sourceBase,
                        sourceCount,
                        nil,
                        COMPRESSION_ZLIB
                    )
                }
                if compressedCount > 0 {
                    destination.count = compressedCount
                    return destination
                }
                destinationSize *= 2
            }
            return nil
        }
    }

    private static func zlibDecompress(_ data: Data, maxOutputBytes: Int) -> Data? {
        if data.isEmpty {
            return Data()
        }

        return data.withUnsafeBytes { sourceBuffer in
            guard let sourceBase = sourceBuffer.bindMemory(to: UInt8.self).baseAddress else {
                return nil
            }
            var destination = Data(count: maxOutputBytes)
            let decompressedCount = destination.withUnsafeMutableBytes { destinationBuffer in
                guard let destinationBase = destinationBuffer.bindMemory(to: UInt8.self).baseAddress else {
                    return 0
                }
                return compression_decode_buffer(
                    destinationBase,
                    maxOutputBytes,
                    sourceBase,
                    data.count,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
            guard decompressedCount > 0 else {
                return nil
            }
            destination.count = decompressedCount
            return destination
        }
    }
}
