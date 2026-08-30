// SPDX-License-Identifier: MIT
import Foundation

struct SendOutcome {
    let accepted: Bool
    let label: String
}

struct StatusPollOutcome {
    let verified: VerifiedIncidentStatus?
}

final class RedirectRejector: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

final class Transport {
    private let config: RuntimeConfig
    private let session: URLSession
    private let redirects = RedirectRejector()
    private let statusEndpoint: URL

    init(config: RuntimeConfig) {
        self.config = config
        statusEndpoint = config.endpoint
            .deletingLastPathComponent()
            .appendingPathComponent("status")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        session = URLSession(configuration: configuration)
    }

    func send(_ event: IncidentEvent) async -> SendOutcome {
        let body = Data(event.wireJSON().utf8)
        guard (1...1024).contains(body.count) else { return pending("invalid local event size") }
        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.httpBody = body
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue(event.requestSignature, forHTTPHeaderField: "X-SPB-Signature")
        do {
            let (bytes, response) = try await session.bytes(for: request, delegate: redirects)
            let contentType = (response as? HTTPURLResponse)?
                .value(forHTTPHeaderField: "Content-Type")
            let mediaType = contentType.map {
                String($0.split(separator: ";", maxSplits: 1).first ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .lowercased()
            }
            guard
                let http = response as? HTTPURLResponse,
                http.statusCode == 202,
                mediaType == "application/json"
            else {
                return pending("relay did not durably accept")
            }
            var responseBody = Data()
            responseBody.reserveCapacity(256)
            for try await byte in bytes {
                guard responseBody.count < 512 else { return pending("relay response was too large") }
                responseBody.append(byte)
            }
            guard Protocol.verifyAcceptedResponse(responseBody, event: event, authKey: config.authKey) else {
                return pending("relay evidence was invalid")
            }
            return SendOutcome(
                accepted: true,
                label: "Relay durably accepted \(event.kind); delivery not confirmed"
            )
        } catch {
            return pending("network unavailable")
        }
    }

    func sendStatus(_ query: StatusQuery) async -> StatusPollOutcome {
        let body = Data(query.wireJSON().utf8)
        guard (1...1024).contains(body.count) else { return StatusPollOutcome(verified: nil) }
        var request = URLRequest(url: statusEndpoint)
        request.httpMethod = "POST"
        request.httpBody = body
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue(query.requestSignature, forHTTPHeaderField: "X-SPB-Signature")
        do {
            let (bytes, response) = try await session.bytes(for: request, delegate: redirects)
            let contentType = (response as? HTTPURLResponse)?
                .value(forHTTPHeaderField: "Content-Type")
            let mediaType = contentType.map {
                String($0.split(separator: ";", maxSplits: 1).first ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .lowercased()
            }
            guard
                let http = response as? HTTPURLResponse,
                http.statusCode == 200,
                mediaType == "application/json"
            else {
                return StatusPollOutcome(verified: nil)
            }
            var responseBody = Data()
            responseBody.reserveCapacity(256)
            for try await byte in bytes {
                guard responseBody.count < 512 else { return StatusPollOutcome(verified: nil) }
                responseBody.append(byte)
            }
            let receivedAt = Int64(Date().timeIntervalSince1970.rounded(.towardZero))
            return StatusPollOutcome(verified: Protocol.verifyStatusResponse(
                responseBody,
                query: query,
                authKey: config.authKey,
                receivedAt: receivedAt
            ))
        } catch {
            return StatusPollOutcome(verified: nil)
        }
    }

    private func pending(_ reason: String) -> SendOutcome {
        SendOutcome(
            accepted: false,
            label: "Stored on watch — relay acceptance pending (\(reason))"
        )
    }
}
