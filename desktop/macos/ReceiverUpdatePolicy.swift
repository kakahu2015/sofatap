import Foundation

/// Pure rules shared by the production adapter and offline regression tests.
enum ReceiverUpdatePolicy {
    static func allowsUpdater(arguments: [String], bundlePath: String, bundleIdentifier: String?,
                              isStore: Bool, isFixture: Bool) -> Bool {
        !isStore && !isFixture
            && (bundleIdentifier == "org.kakahu.hosttap" || bundleIdentifier == "com.chekusu.sofatap.receiver")
            && bundlePath.hasSuffix(".app")
            && !arguments.contains("--dry-run")
            && !arguments.contains(where: { $0.hasPrefix("--test-display") })
    }

    static func allowedChannels(releaseVersion: String?) -> Set<String> {
        guard let releaseVersion, releaseVersion.range(of: #"^\d+\.\d+\.\d+-(alpha|beta|rc)\.\d+$"#, options: .regularExpression) != nil else { return [] }
        return ["beta"]
    }

    /// Unknown/starting receiver status is not evidence that it is safe to restart.
    static func mayResumeInstall(connected: Bool?, isStarting: Bool, isStopping: Bool) -> Bool {
        connected == false && !isStarting && !isStopping
    }
}
