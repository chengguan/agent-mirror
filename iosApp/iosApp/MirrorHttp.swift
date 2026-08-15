import Foundation
import CryptoKit
import Shared

/// URLSession client that pins the companion leaf cert SHA-256 (OWASP M5).
/// CryptoKit hashes the DER; ATS stays enabled in Info.plist.
final class PinnedCompanionClient: NSObject, IosHttpClient {
    func request(
        method: String,
        url: String,
        token: String,
        pin: String,
        host: String,
        body: String?,
        onResult: @escaping (String?, String?) -> Void
    ) {
        guard let requestUrl = URL(string: url), requestUrl.scheme == "https" else {
            onResult(nil, "bad url")
            return
        }
        let delegate = PinDelegate(pin: pin.lowercased(), host: host)
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.timeoutIntervalForRequest = method == "POST" ? 180 : 20
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        delegate.session = session

        var req = URLRequest(url: requestUrl)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data(body.utf8)
        }
        let task = session.dataTask(with: req) { data, response, error in
            defer { session.invalidateAndCancel() }
            if let error {
                onResult(nil, "network")
                return
            }
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200...299).contains(code), let data else {
                onResult(nil, "http \(code)")
                return
            }
            onResult(String(data: data, encoding: .utf8) ?? "", nil)
        }
        task.resume()
    }
}

private final class PinDelegate: NSObject, URLSessionDelegate {
    let pin: String
    let host: String
    var session: URLSession?

    init(pin: String, host: String) {
        self.pin = pin
        self.host = host
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              challenge.protectionSpace.host == host
        else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let leaf: SecCertificate?
        if let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] {
            leaf = chain.first
        } else {
            leaf = SecTrustGetCertificateAtIndex(trust, 0)
        }
        guard let cert = leaf else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let der = SecCertificateCopyData(cert) as Data
        let digest = SHA256.hash(data: der)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        if timingSafeEqual(hex, pin) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

private func timingSafeEqual(_ left: String, _ right: String) -> Bool {
    guard left.utf8.count == right.utf8.count else { return false }
    var acc: UInt8 = 0
    for (a, b) in zip(left.utf8, right.utf8) {
        acc |= a ^ b
    }
    return acc == 0
}
