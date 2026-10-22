import Foundation
import Security
import CryptoKit

protocol SavedDeviceCredential: Codable, Equatable {
    var deviceIdentity: String { get }
    var deviceAddress: String { get }
    var pairingName: String? { get }
    var pairingPlatform: String? { get }
    func validateForStorage() throws
}

extension SavedDeviceCredential {
    var pairingName: String? { nil }
    var pairingPlatform: String? { nil }
}

/// Names and credentials share the same device-only Keychain record. No bearer
/// credential, certificate or local device inventory is written to UserDefaults.
struct SavedDevice<Credential: SavedDeviceCredential>: Codable, Equatable, Identifiable {
    let id: UUID
    var credential: Credential
    var alias: String?
    var reportedName: String?
    var platform: String?
    var displayName: String { alias ?? reportedName ?? credential.deviceAddress }

    init(credential: Credential) {
        // Stable across a migration retry or refreshed token; credentials are not
        // part of this identifier. Each adapter has its own identity namespace.
        let hash = Array(SHA256.hash(data: Data(credential.deviceIdentity.utf8)))
        id = UUID(uuid: (hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
                         hash[8], hash[9], hash[10], hash[11], hash[12], hash[13], hash[14], hash[15]))
        self.credential = credential
        // Only new records receive QR hints. Refreshing credentials preserves
        // authenticated metadata and the user's local alias.
        reportedName = credential.pairingName
        platform = credential.pairingPlatform
    }
}

struct DeviceLibrary<Credential: SavedDeviceCredential>: Codable, Equatable {
    var devices: [SavedDevice<Credential>] = []
    var selectedID: UUID?
    var selected: SavedDevice<Credential>? { devices.first { $0.id == selectedID } }

    mutating func upsert(_ credential: Credential) {
        let incoming = SavedDevice(credential: credential)
        if let index = devices.firstIndex(where: { $0.id == incoming.id }) {
            devices[index].credential = credential
        } else { devices.append(incoming) }
        selectedID = incoming.id
    }

    mutating func select(_ id: UUID) throws {
        guard devices.contains(where: { $0.id == id }) else { throw DeviceLibraryError.missingDevice }
        selectedID = id
    }

    mutating func rename(_ id: UUID, name: String) throws {
        guard let index = devices.firstIndex(where: { $0.id == id }) else { throw DeviceLibraryError.missingDevice }
        let value = try Self.cleanName(name)
        devices[index].alias = value
    }

    mutating func updateMetadata(_ id: UUID, name: String?, platform: String?) throws {
        guard let index = devices.firstIndex(where: { $0.id == id }) else { throw DeviceLibraryError.missingDevice }
        if let name { devices[index].reportedName = try Self.cleanName(name) }
        if let platform, ["windows", "macos", "linux", "unknown", "webos"].contains(platform) {
            devices[index].platform = platform
        }
    }

    mutating func remove(_ id: UUID) {
        devices.removeAll { $0.id == id }
        // Never select another device implicitly after a removal.
        if selectedID == id { selectedID = nil }
    }

    func validate() throws {
        guard devices.count <= 128, Set(devices.map(\.id)).count == devices.count,
              selectedID == nil || selected != nil else { throw DeviceLibraryError.invalidData }
        for device in devices {
            try device.credential.validateForStorage()
            guard device.id == SavedDevice(credential: device.credential).id else { throw DeviceLibraryError.invalidData }
            if let alias = device.alias { _ = try Self.cleanName(alias) }
            if let name = device.reportedName { _ = try Self.cleanName(name) }
        }
    }

    private static func cleanName(_ raw: String) throws -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.count <= 80, !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw DeviceLibraryError.invalidName }
        return value.isEmpty ? nil : value
    }
}

enum DeviceLibraryError: LocalizedError {
    case unavailable, invalidData, missingDevice, invalidName
    var errorDescription: String? {
        switch self {
        case .invalidName: return String(localized: "设备名称需在 80 个字符以内，且不能包含换行。")
        case .missingDevice: return String(localized: "这个配对已移除，请重新选择设备。")
        case .unavailable, .invalidData: return String(localized: "无法安全读取或保存设备，请解锁手机后重试。")
        }
    }
}

protocol DeviceLibraryDataStorage {
    func read(account: String) throws -> Data?
    func write(_ data: Data, account: String) throws
    func remove(account: String)
}

/// A single atomic Keychain item preserves selection, aliases and credentials.
/// A successfully saved empty v2 library is a tombstone: legacy data cannot
/// resurrect a removed device. Failed reads/writes never replace existing data.
struct KeychainDeviceLibrary<Credential: SavedDeviceCredential> {
    let service: String
    let legacyAccount: String
    // Tests inject an in-memory backend; the application always uses Keychain.
    var storage: DeviceLibraryDataStorage? = nil
    private func query(_ account: String) -> [String: Any] { [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account
    ] }

    func read() throws -> DeviceLibrary<Credential> {
        if let data = try readData(account: "paired-devices-v2") {
            let library = try JSONDecoder().decode(DeviceLibrary<Credential>.self, from: data)
            try library.validate()
            return library
        }
        var library = DeviceLibrary<Credential>()
        if let data = try readData(account: legacyAccount) {
            let legacy = try JSONDecoder().decode(Credential.self, from: data)
            try legacy.validateForStorage()
            library.upsert(legacy)
            // Commit the complete replacement before touching legacy storage.
            try write(library)
        }
        return library
    }

    func write(_ library: DeviceLibrary<Credential>) throws {
        try library.validate()
        let data = try JSONEncoder().encode(library)
        if let storage {
            try storage.write(data, account: "paired-devices-v2")
            storage.remove(account: legacyAccount)
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let lookup = query("paired-devices-v2")
        let status = SecItemUpdate(lookup as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var request = lookup
            attributes.forEach { request[$0.key] = $0.value }
            guard SecItemAdd(request as CFDictionary, nil) == errSecSuccess else { throw DeviceLibraryError.unavailable }
        } else if status != errSecSuccess { throw DeviceLibraryError.unavailable }
        // This is a private migration cleanup, never remote credential revocation.
        SecItemDelete(query(legacyAccount) as CFDictionary)
    }

    func mutate(_ mutation: (inout DeviceLibrary<Credential>) throws -> Void) throws {
        var library = try read()
        try mutation(&library)
        try write(library)
    }

    private func readData(account: String) throws -> Data? {
        if let storage { return try storage.read(account: account) }
        var request = query(account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw DeviceLibraryError.unavailable }
        return data
    }
}
