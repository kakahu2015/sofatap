import Foundation
import CryptoKit

/// One add-device presentation owns its candidate across camera, paste and URL
/// callbacks. Bootstrap attempts are remembered only in memory, as digests.
struct PairingAttemptState {
    private(set) var candidate: PairingCode?
    private(set) var authenticated = false
    private var rawLink: String?
    private var currentCredential: String?

    /// The scanner asks before stopping capture. Validation must not consume a
    /// bootstrap until its accepted scan actually starts the pairing attempt.
    func canBegin(_ raw: String) -> Bool {
        var preview = self
        return (try? preview.begin(raw)) == true
    }

    /// False means a duplicate of the pending/successful candidate; do nothing.
    mutating func begin(_ raw: String) throws -> Bool {
        let code = try PairingCode.parse(raw)
        let credential = Self.credentialDigest(code)
        if currentCredential == credential { return false }
        candidate = code; rawLink = raw; currentCredential = credential; authenticated = false
        return true
    }

    var computerRetryLink: String? {
        guard !authenticated, case .computer = candidate else { return nil }
        return rawLink
    }

    var pendingCandidate: PairingCode? { authenticated ? nil : candidate }

    mutating func markAuthenticated() { if candidate != nil { authenticated = true } }

    mutating func resetScanner() {
        candidate = nil; rawLink = nil; currentCredential = nil; authenticated = false
    }

    private static func credentialDigest(_ code: PairingCode) -> String {
        switch code {
        case .computer(let pairing):
            return digest(["computer", pairing.host.lowercased(), String(pairing.port), pairing.fingerprint.lowercased(), pairing.token])
        }
    }
    private static func digest(_ values: [String]) -> String {
        SHA256.hash(data: Data(values.joined(separator: "\u{0}").utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
