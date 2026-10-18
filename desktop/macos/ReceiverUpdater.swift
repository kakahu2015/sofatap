import AppKit
import Combine

#if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
import Sparkle
#endif

/// Website packages use Sparkle; store packages never link or start an external updater.
@MainActor
final class ReceiverUpdater: NSObject, NSMenuItemValidation {
    private weak var receiver: ReceiverController?
    private var isEnabled = false
    private var checkItem: NSMenuItem?
    private var automaticItem: NSMenuItem?
    #if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
    private var controller: SPUStandardUpdaterController?
    private var observation: AnyCancellable?
    private var postponedInstall: (() -> Void)?
    #endif

    init(receiver: ReceiverController) {
        self.receiver = receiver
        super.init()
    }

    func addMenuItems(to menu: NSMenu) {
        #if SOFATAP_MAC_APP_STORE
        // The Mac listing is not published yet. Open Apple's Updates page instead
        // of inventing an app id or routing a sandboxed build to a website installer.
        let item = menu.addItem(withTitle: NSLocalizedString("Check for Updates in the App Store…", comment: ""), action: #selector(openStoreUpdates), keyEquivalent: "")
        item.target = self
        #elseif SOFATAP_SPARKLE && !SOFATAP_UI_FIXTURE
        let check = menu.addItem(withTitle: NSLocalizedString("Check for Updates…", comment: ""), action: #selector(checkForUpdates), keyEquivalent: "")
        check.target = self
        checkItem = check
        let automatic = menu.addItem(withTitle: NSLocalizedString("Automatically Check for and Download Updates", comment: ""), action: #selector(toggleAutomaticUpdates), keyEquivalent: "")
        automatic.target = self
        automaticItem = automatic
        #endif
    }

    func start() {
        #if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
        guard ReceiverUpdatePolicy.allowsUpdater(arguments: Array(CommandLine.arguments.dropFirst()),
            bundlePath: Bundle.main.bundlePath, bundleIdentifier: Bundle.main.bundleIdentifier,
            isStore: false, isFixture: false) else { return }
        controller = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: self, userDriverDelegate: nil)
        controller?.startUpdater()
        isEnabled = true
        // Only user-requested Install & Relaunch waits here. Automatic downloads
        // follow Sparkle's normal install-on-quit path and never force a restart.
        observation = receiver?.$state.dropFirst().sink { [weak self] _ in
            // Published delivers before the value changes; re-read on the next turn.
            DispatchQueue.main.async { self?.resumePostponedInstallIfIdle() }
        }
        #endif
    }

    @objc private func openStoreUpdates() {
        #if SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
        guard !CommandLine.arguments.contains("--dry-run"),
              !CommandLine.arguments.contains(where: { $0.hasPrefix("--test-display") }) else { return }
        NSWorkspace.shared.open(URL(string: "macappstore://showUpdatesPage")!)
        #endif
    }

    @objc private func checkForUpdates() {
        #if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
        guard isEnabled else { return }
        controller?.checkForUpdates(nil)
        #endif
    }

    @objc private func toggleAutomaticUpdates() {
        #if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
        guard isEnabled, let updater = controller?.updater else { return }
        let enabled = !(updater.automaticallyChecksForUpdates && updater.automaticallyDownloadsUpdates)
        updater.automaticallyChecksForUpdates = enabled
        updater.automaticallyDownloadsUpdates = enabled
        #endif
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        #if SOFATAP_MAC_APP_STORE
        return !CommandLine.arguments.contains("--dry-run")
            && !CommandLine.arguments.contains(where: { $0.hasPrefix("--test-display") })
        #elseif SOFATAP_SPARKLE && !SOFATAP_UI_FIXTURE
        guard isEnabled, let updater = controller?.updater else { return false }
        if menuItem === automaticItem {
            menuItem.state = updater.automaticallyChecksForUpdates && updater.automaticallyDownloadsUpdates ? .on : .off
            return true
        }
        if menuItem === checkItem { return updater.canCheckForUpdates }
        return true
        #else
        return false
        #endif
    }

    #if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
    private func resumePostponedInstallIfIdle() {
        guard let receiver, ReceiverUpdatePolicy.mayResumeInstall(connected: receiver.state?.connected,
            isStarting: receiver.isStarting, isStopping: receiver.isStopping),
            let install = postponedInstall else { return }
        postponedInstall = nil
        install()
    }
    #endif
}

#if SOFATAP_SPARKLE && !SOFATAP_MAC_APP_STORE && !SOFATAP_UI_FIXTURE
extension ReceiverUpdater: SPUUpdaterDelegate {
    func allowedChannels(for updater: SPUUpdater) -> Set<String> {
        ReceiverUpdatePolicy.allowedChannels(releaseVersion: Bundle.main.object(forInfoDictionaryKey: "SofatapReleaseVersion") as? String)
    }

    func updater(_ updater: SPUUpdater, shouldPostponeRelaunchForUpdate item: SUAppcastItem,
                 untilInvokingBlock installHandler: @escaping () -> Void) -> Bool {
        guard let receiver else { return true }
        if ReceiverUpdatePolicy.mayResumeInstall(connected: receiver.state?.connected,
            isStarting: receiver.isStarting, isStopping: receiver.isStopping) { return false }
        postponedInstall = installHandler
        let alert = NSAlert()
        alert.messageText = NSLocalizedString("Update Ready", comment: "")
        alert.informativeText = NSLocalizedString("Disconnect your phone from this Mac first. HostTap Remote will then install the update and restart.", comment: "")
        alert.addButton(withTitle: NSLocalizedString("OK", comment: ""))
        if let window = NSApp.windows.first(where: \.isVisible) { alert.beginSheetModal(for: window) }
        else { alert.runModal() }
        return true
    }

    func updater(_ updater: SPUUpdater, didAbortWithError error: Error) {
        postponedInstall = nil
    }
}
#endif
