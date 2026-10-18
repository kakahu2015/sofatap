import Combine
import Foundation
import ServiceManagement

/// The OS is the authority for enabled state. A saved first-run marker is only
/// used to avoid undoing a later decision in System Settings or retrying failures.
enum ReceiverLoginStatus: Equatable {
    case notRegistered, enabled, requiresApproval, unavailable
}

enum ReceiverLoginFirstLaunchPolicy {
    case automaticRegistration, explicitConsent

    static var distributionDefault: Self {
        #if SOFATAP_MAC_APP_STORE
        return .explicitConsent
        #else
        return .automaticRegistration
        #endif
    }
}

@MainActor
protocol ReceiverLoginService: AnyObject {
    var status: ReceiverLoginStatus { get }
    func register() throws
    func unregister() throws
}

@MainActor
protocol ReceiverLoginPreferences: AnyObject {
    var hasConfiguredLoginItem: Bool { get set }
}

@MainActor
final class ReceiverLoginItem: ObservableObject {
    @Published private(set) var status: ReceiverLoginStatus = .unavailable
    @Published private(set) var failure: String?
    @Published private(set) var needsFirstLaunchConsent = false
    @Published var firstLaunchRequested = true
    let isAvailable: Bool
    private let service: ReceiverLoginService
    private let preferences: ReceiverLoginPreferences
    private let firstLaunchPolicy: ReceiverLoginFirstLaunchPolicy

    // Checked means registered/requested; a pending OS authorization is always
    // separately labeled and must never be presented as an active login item.
    var isRequested: Bool { status == .enabled || status == .requiresApproval }

    init(isAvailable: Bool, service: ReceiverLoginService, preferences: ReceiverLoginPreferences,
         firstLaunchPolicy: ReceiverLoginFirstLaunchPolicy = .distributionDefault) {
        self.isAvailable = isAvailable
        self.service = service
        self.preferences = preferences
        self.firstLaunchPolicy = firstLaunchPolicy
    }

    static func production(arguments: [String], bundle: Bundle = .main) -> ReceiverLoginItem {
        #if SOFATAP_UI_FIXTURE
        let available = false
        #else
        let available = canManage(arguments: arguments, bundleIdentifier: bundle.bundleIdentifier,
                                  bundleURL: bundle.bundleURL)
        #endif
        return ReceiverLoginItem(isAvailable: available, service: SystemReceiverLoginService(),
                                 preferences: StandardReceiverLoginPreferences())
    }

    static func canManage(arguments: [String], bundleIdentifier: String?, bundleURL: URL) -> Bool {
        (bundleIdentifier == "com.chekusu.sofatap.receiver" || bundleIdentifier == "org.kakahu.hosttap") && bundleURL.pathExtension == "app"
            && !arguments.contains("--dry-run")
            && !arguments.contains(where: { $0.hasPrefix("--test-display-id") })
    }

    /// Invoke once after normal application startup, never from a view initializer.
    func configureFirstLaunch() {
        guard isAvailable else { return }
        refresh()
        guard !preferences.hasConfiguredLoginItem else { return }
        if firstLaunchPolicy == .explicitConsent && status == .notRegistered {
            // A preselected first-run choice is not consent. Only the native
            // confirmation button below may register the store application's item.
            needsFirstLaunchConsent = true
            return
        }
        // Persist before the system call: failure/rejection must not cause a
        // registration request every time the app is opened.
        preferences.hasConfiguredLoginItem = true
        if status == .notRegistered { setRequested(true) }
    }

    func confirmFirstLaunch() {
        guard isAvailable, needsFirstLaunchConsent else { return }
        needsFirstLaunchConsent = false
        setRequested(firstLaunchRequested)
    }

    /// A read only refresh reconciles System Settings changes without re-enabling.
    func refresh() {
        guard isAvailable else { return }
        status = service.status
    }

    func setRequested(_ requested: Bool) {
        guard isAvailable else { return }
        preferences.hasConfiguredLoginItem = true
        failure = nil
        refresh()
        do {
            if requested {
                if status == .notRegistered || status == .unavailable { try service.register() }
                // Already registered but denied/revoked must be approved in
                // System Settings. Do not unregister/register to defeat that choice.
            } else if status == .enabled || status == .requiresApproval {
                try service.unregister()
            }
        } catch {
            failure = requested ? NSLocalizedString("Unable to enable launch at login. Check System Settings.", comment: "") : NSLocalizedString("Unable to disable launch at login. Check System Settings.", comment: "")
        }
        refresh()
    }

    func openSystemSettings() {
        guard isAvailable else { return }
        SMAppService.openSystemSettingsLoginItems()
    }
}

@MainActor
private final class StandardReceiverLoginPreferences: ReceiverLoginPreferences {
    private let key = "receiver.loginItem.configured.v1"
    var hasConfiguredLoginItem: Bool {
        get { UserDefaults.standard.bool(forKey: key) }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }
}

@MainActor
private final class SystemReceiverLoginService: ReceiverLoginService {
    var status: ReceiverLoginStatus {
        switch SMAppService.mainApp.status {
        case .notRegistered: return .notRegistered
        case .enabled: return .enabled
        case .requiresApproval: return .requiresApproval
        case .notFound: return .unavailable
        @unknown default: return .unavailable
        }
    }

    func register() throws { try SMAppService.mainApp.register() }
    func unregister() throws { try SMAppService.mainApp.unregister() }
}
