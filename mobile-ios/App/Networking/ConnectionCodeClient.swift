import Foundation

enum ConnectionCodeClient {
    private static let timeoutSeconds: TimeInterval = 10

    static func fetchInitPayload(_ connectionCode: ConnectionCodePayload) async throws -> String {
        let request = try makeRequest(
            connectionCode: connectionCode,
            path: "/pairing/init",
            method: "GET",
            body: nil
        )
        return try await runRequest(request, successStatusCode: 200)
    }

    static func submitConfirmPayload(
        _ connectionCode: ConnectionCodePayload,
        confirmPayload: String
    ) async throws {
        let request = try makeRequest(
            connectionCode: connectionCode,
            path: "/pairing/confirm",
            method: "POST",
            body: Data(confirmPayload.utf8)
        )
        _ = try await runRequest(request, successStatusCode: 202)
    }

    private static func makeRequest(
        connectionCode: ConnectionCodePayload,
        path: String,
        method: String,
        body: Data?
    ) throws -> URLRequest {
        var components = URLComponents()
        components.scheme = "http"
        components.host = connectionCode.host
        components.port = connectionCode.port
        components.path = path
        components.queryItems = [URLQueryItem(name: "token", value: connectionCode.token)]

        guard let url = components.url else {
            throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_connection_code"))
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeoutSeconds
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("text/plain", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    private static func runRequest(
        _ request: URLRequest,
        successStatusCode: Int
    ) async throws -> String {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeoutSeconds
        configuration.timeoutIntervalForResource = timeoutSeconds
        let session = URLSession(configuration: configuration)

        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                throw SessionFailure(code: .peerUnreachable, message: L10n.tr("error.peer_unreachable"))
            }

            let body = String(data: data, encoding: .utf8) ?? ""
            if httpResponse.statusCode == successStatusCode {
                return body
            }

            switch httpResponse.statusCode {
            case 410:
                throw SessionFailure(code: .sessionExpired, message: L10n.tr("error.session_expired"))
            case 400, 401, 404, 409:
                throw SessionFailure(code: .invalidPayload, message: L10n.tr("error.invalid_connection_code"))
            default:
                throw SessionFailure(
                    code: .peerUnreachable,
                    message: L10n.tr("error.peer_unreachable_http", httpResponse.statusCode)
                )
            }
        } catch let failure as SessionFailure {
            throw failure
        } catch {
            throw SessionFailure(code: .peerUnreachable, message: L10n.tr("error.peer_unreachable"))
        }
    }
}
