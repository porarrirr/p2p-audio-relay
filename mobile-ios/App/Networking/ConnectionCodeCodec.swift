import Foundation

enum ConnectionCodeCodec {
    static let prefix = "p2paudio-c1:"

    static func looksLikeConnectionCode(_ raw: String?) -> Bool {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return false
        }
        return trimmed.hasPrefix(prefix)
    }

    static func encode(_ payload: ConnectionCodePayload) throws -> String {
        guard !payload.host.isEmpty,
              (1...65_535).contains(payload.port),
              !payload.token.isEmpty,
              payload.expiresAtUnixMs > 0 else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_connection_code"))
        }
        return "\(prefix)\(payload.host):\(payload.port):\(payload.expiresAtUnixMs):\(payload.token)"
    }

    static func decode(_ raw: String) throws -> ConnectionCodePayload {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(prefix) else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_connection_code"))
        }

        let body = String(trimmed.dropFirst(prefix.count))
        let parts = body.split(separator: ":", maxSplits: 3, omittingEmptySubsequences: false)
        guard parts.count == 4 else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_connection_code"))
        }

        let host = String(parts[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        let port = Int(parts[1])
        let expiresAtUnixMs = Int64(parts[2])
        let token = String(parts[3]).trimmingCharacters(in: .whitespacesAndNewlines)

        guard !host.isEmpty,
              let port,
              (1...65_535).contains(port),
              let expiresAtUnixMs,
              expiresAtUnixMs > 0,
              !token.isEmpty else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_connection_code"))
        }

        return ConnectionCodePayload(
            host: host,
            port: port,
            token: token,
            expiresAtUnixMs: expiresAtUnixMs
        )
    }
}
