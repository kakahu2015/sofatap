import Foundation

private final class MemoryDataStore: DeviceLibraryDataStorage {
    var values: [String: Data] = [:]
    var failRead = false
    var failWrite = false
    func read(account: String) throws -> Data? {
        if failRead { throw DeviceLibraryError.unavailable }
        return values[account]
    }
    func write(_ data: Data, account: String) throws {
        if failWrite { throw DeviceLibraryError.unavailable }
        values[account] = data
    }
    func remove(account: String) { values.removeValue(forKey: account) }
}

@main struct SavedDeviceTests {
    @MainActor static func main() throws {
        var count = 0
        func check(_ label: String, _ value: @autoclosure () -> Bool) {
            guard value() else { fatalError("FAIL: \(label)") }
            count += 1
        }
        func rejects(_ label: String, _ action: () throws -> Void) {
            do { try action(); fatalError("FAIL: \(label)") }
            catch { count += 1 }
        }
        let a = Pairing(host: "192.168.1.2", port: 53317, token: String(repeating: "a", count: 43), fingerprint: String(repeating: "a", count: 64))
        let b = Pairing(host: "192.168.1.3", port: 53317, token: String(repeating: "b", count: 43), fingerprint: String(repeating: "b", count: 64))
        let storage = MemoryDataStore()
        storage.values["legacy"] = try JSONEncoder().encode(a)
        let store = KeychainDeviceLibrary<Pairing>(service: "fixture", legacyAccount: "legacy", storage: storage)
        storage.failWrite = true
        rejects("failed migration leaves legacy available") { _ = try store.read() }
        check("failed migration never removes legacy", storage.values["legacy"] != nil && storage.values["paired-devices-v2"] == nil)
        storage.failWrite = false
        let migrated = try store.read()
        check("single pairing migrates selected and intact", migrated.devices.count == 1 && migrated.selected?.credential == a)
        check("migration persists replacement before removing legacy", storage.values["legacy"] == nil && storage.values["paired-devices-v2"] != nil)
        let idA = migrated.selectedID!
        try store.mutate { try $0.rename(idA, name: "  Living room  ") }
        try store.mutate { try $0.updateMetadata(idA, name: "GAMING-PC", platform: "windows") }
        try store.mutate { $0.upsert(b) }
        let both = try store.read()
        let idB = both.selectedID!
        check("adding second device preserves first and selects second", both.devices.count == 2 && both.selected?.credential == b)
        check("alias wins without overwriting reported name", both.devices.first?.displayName == "Living room" && both.devices.first?.reportedName == "GAMING-PC")
        let refreshed = Pairing(host: "192.168.1.50", port: 53318, token: String(repeating: "c", count: 43), fingerprint: a.fingerprint)
        try store.mutate { $0.upsert(refreshed) }
        let refreshedLibrary = try store.read()
        check("token and address refresh preserve stable device and alias", refreshedLibrary.devices.count == 2 && refreshedLibrary.selectedID == idA && refreshedLibrary.selected?.displayName == "Living room")
        check("refresh updates only the intended credential", refreshedLibrary.selected?.credential == refreshed && refreshedLibrary.devices.last?.credential == b)
        // A fresh store instance models reopening the app. Scanning the same
        // receiver again must not add a row or discard the user's nickname.
        let reopenedStore = KeychainDeviceLibrary<Pairing>(service: "fixture", legacyAccount: "legacy", storage: storage)
        let reopened = try reopenedStore.read()
        check("cold reopen restores the exact selected receiver", reopened.selectedID == idA && reopened.selected?.credential == refreshed)
        try reopenedStore.mutate { $0.upsert(refreshed) }
        try reopenedStore.mutate { $0.upsert(refreshed) }
        let rescanned = try reopenedStore.read()
        check("repeated pairing after reopen retains one row per receiver", rescanned.devices.count == 2 && rescanned.devices.filter { $0.id == idA }.count == 1)
        check("repeated pairing after reopen preserves nickname and other device", rescanned.selected?.displayName == "Living room" && rescanned.devices.last?.credential == b)
        try store.mutate { $0.remove(idB) }
        let removedInactive = try store.read()
        check("inactive removal leaves selection untouched", removedInactive.selectedID == idA && removedInactive.devices.count == 1)
        try store.mutate { $0.upsert(b) }
        try store.mutate { $0.remove(idB) }
        let removedActive = try store.read()
        check("active removal does not fall back to remaining device", removedActive.devices.count == 1 && removedActive.selectedID == nil)
        rejects("select missing device fails") { try store.mutate { try $0.select(idB) } }
        let unmodified = storage.values
        rejects("control characters in name rejected") { try store.mutate { try $0.rename(idA, name: "Living\nroom") } }
        rejects("oversize name rejected") { try store.mutate { try $0.rename(idA, name: String(repeating: "x", count: 81)) } }
        check("invalid updates preserve persisted data", storage.values == unmodified)
        try store.mutate { try $0.rename(idA, name: " ") }
        let noAlias = try store.read()
        check("empty alias resets to receiver name", noAlias.devices.first?.displayName == "GAMING-PC")
        try store.mutate { $0.remove(idA) }
        storage.values["legacy"] = try JSONEncoder().encode(a)
        let noResurrection = try store.read()
        check("empty library prevents legacy resurrection", noResurrection.devices.isEmpty)
        storage.failRead = true
        let beforeFailedRead = storage.values
        rejects("locked or unreadable Keychain cannot be overwritten") { try store.mutate { $0.upsert(b) } }
        check("read failure retains all persisted bytes", storage.values == beforeFailedRead)
        storage.failRead = false
        storage.values["paired-devices-v2"] = Data("corrupt".utf8)
        rejects("corrupt collection does not silently replace legacy") { _ = try store.read() }
        check("corrupt collection remains available for recovery", storage.values["paired-devices-v2"] == Data("corrupt".utf8))

        let webA = WebOSTVPairing(host: "192.168.1.2", fingerprint: a.fingerprint, clientKey: "fixture-client-key")
        let webB = WebOSTVPairing(host: "192.168.1.3", fingerprint: a.fingerprint, clientKey: "fixture-other-key")
        check("webOS shared certificates do not merge different TV endpoints", SavedDevice(credential: webA).id != SavedDevice(credential: webB).id)
        try webA.validateForStorage()
        print("PASS \(count) saved-device migration, selection, privacy and persistence checks; no Keychain, network or input access")
    }
}
