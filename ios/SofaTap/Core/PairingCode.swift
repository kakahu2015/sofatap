import Foundation

/// A single scan routes only to supported QR protocols. Each parser still owns
/// its strict fields, private-network policy, bearer token and certificate pin.
enum PairingCode: Equatable {
    case computer(Pairing)

    static func parse(_ raw: String) throws -> Self {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.utf8.count <= 2048, let url = URLComponents(string: text),
              url.scheme?.lowercased() == "sofatap" else { throw PairingCodeError.invalid }
        do {
            switch url.host {
            case "pair": return .computer(try Pairing.parse(text))
            default: throw PairingCodeError.invalid
            }
        } catch { throw PairingCodeError.invalid }
    }
}

enum PairingCodeError: LocalizedError {
    case invalid
    var errorDescription: String? {
        String(localized: "这不是有效的 Sofatap 配对二维码或链接。请在接收器中重新生成。")
    }
}
