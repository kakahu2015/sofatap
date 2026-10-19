import AppKit
import CoreServices
import ImageIO
import SwiftUI

#if !SOFATAP_UI_FIXTURE
@main
enum SofaTapLauncher {
    @MainActor static func main() {
        let options: ReceiverLaunchOptions
        do { options = try ReceiverLaunchOptions.parse(Array(CommandLine.arguments.dropFirst())) }
        catch { failLaunch(error) }
        let application = NSApplication.shared
        // Resolve before entering the application loop or creating a window/helper.
        if let id = options.testDisplayID {
            do { _ = try ReceiverTestScreen.frame(displayID: id) }
            catch { failLaunch(error) }
        }
        let delegate = LauncherDelegate(options: options)
        application.delegate = delegate
        if options.testDisplayID != nil {
            guard application.setActivationPolicy(.accessory) else {
                failLaunch(ReceiverLaunchError.displayUnavailable)
            }
        } else {
            application.setActivationPolicy(.regular)
        }
        withExtendedLifetime(delegate) { application.run() }
    }

    private static func failLaunch(_ error: Error) -> Never {
        FileHandle.standardError.write(Data("Sofatap: \(error)\n".utf8))
        exit(64)
    }
}

#endif

@MainActor
final class LauncherDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private let receiver = ReceiverController()
    private let options: ReceiverLaunchOptions
    private lazy var updater = ReceiverUpdater(receiver: receiver)
    private let loginItem: ReceiverLoginItem
    private var window: NSWindow?
    private var isTerminating = false

    init(options: ReceiverLaunchOptions) {
        self.options = options
        self.loginItem = ReceiverLoginItem.production(arguments: Array(CommandLine.arguments.dropFirst()))
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let testFrame: NSRect?
        do { testFrame = try options.testDisplayID.map { try ReceiverTestScreen.frame(displayID: $0) } }
        catch {
            FileHandle.standardError.write(Data("Sofatap: \(error)\n".utf8))
            NSApp.terminate(nil)
            return
        }
        let mainMenu = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: NSLocalizedString("About HostTap Remote", comment: ""), action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        updater.addMenuItems(to: appMenu)
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: NSLocalizedString("Hide HostTap Remote", comment: ""), action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(withTitle: NSLocalizedString("Quit HostTap Remote", comment: ""), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        mainMenu.addItem(appItem)
        NSApp.mainMenu = mainMenu

        let window: NSWindow
        if testFrame != nil {
            let panel = ReceiverTestPanel(contentRect: NSRect(x: 0, y: 0, width: 470, height: 700),
                styleMask: [.titled, .closable, .nonactivatingPanel], backing: .buffered, defer: false)
            panel.isFloatingPanel = false
            panel.level = .normal
            panel.hidesOnDeactivate = false
            panel.isMovable = false
            panel.animationBehavior = .none
            window = panel
        } else {
            window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 470, height: 700),
                styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        }
        window.title = "HostTap Remote"
        window.appearance = NSAppearance(named: .darkAqua)
        window.titlebarAppearsTransparent = true
        window.backgroundColor = NSColor(red: 17 / 255, green: 25 / 255, blue: 27 / 255, alpha: 1)
        window.contentMinSize = NSSize(width: 380, height: 440)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.contentView = NSHostingView(rootView: ReceiverView(receiver: receiver, loginItem: loginItem))
        self.window = window
        if let testFrame {
            window.setFrame(testFrame, display: false)
            window.orderFrontRegardless()
            NotificationCenter.default.addObserver(self, selector: #selector(testDisplayChanged),
                name: NSApplication.didChangeScreenParametersNotification, object: nil)
        } else {
            window.center()
            // Login items should prepare the receiver without stealing focus or
            // opening a pairing window over the user's restored applications.
            let launchEvent = NSAppleEventManager.shared().currentAppleEvent
            let launchedAtLogin = launchEvent?.eventID == kAEOpenApplication
                && launchEvent?.paramDescriptor(forKeyword: keyAEPropData)?.enumCodeValue == keyAELaunchedAsLogInItem
            if !launchedAtLogin {
                window.makeKeyAndOrderFront(nil)
                NSApp.activate(ignoringOtherApps: true)
            }
        }
        receiver.start()
        loginItem.configureFirstLaunch()
        updater.start()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag {
            if options.testDisplayID != nil { testDisplayChanged() }
            else { window?.makeKeyAndOrderFront(nil) }
        }
        return true
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        loginItem.refresh()
    }

    @objc private func testDisplayChanged() {
        guard !isTerminating, let id = options.testDisplayID else { return }
        do {
            let frame = try ReceiverTestScreen.frame(displayID: id)
            window?.setFrame(frame, display: false)
            window?.orderFrontRegardless()
        } catch {
            // Never let AppKit recover a missing QA display onto a personal screen.
            window?.orderOut(nil)
            NSApp.terminate(nil)
        }
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if options.testDisplayID != nil || CommandLine.arguments.contains("--dry-run") {
            NSApp.terminate(nil)
        } else {
            // Closing the pairing window must not disconnect a paired phone.
            // Dock reopening restores it; the native Quit command stops the app.
            sender.orderOut(nil)
        }
        return false
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        isTerminating = true
        receiver.stop { [weak self] stopped in
            // Reply on the next main-loop turn, after AppKit receives terminateLater.
            DispatchQueue.main.async {
                sender.reply(toApplicationShouldTerminate: stopped)
                if !stopped { self?.isTerminating = false }
                if !stopped, self?.options.testDisplayID == nil { self?.window?.makeKeyAndOrderFront(nil) }
            }
        }
        return .terminateLater
    }
}

/// A visible, nonactivating receiver for camera/transport QA only. It cannot take
/// key focus, and the corresponding launch option always forces dry-run input.
private final class ReceiverTestPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

@MainActor
private enum ReceiverTestScreen {
    static func frame(displayID: UInt32) throws -> NSRect {
        let displays = NSScreen.screens.compactMap { screen -> ReceiverDisplaySnapshot? in
            guard let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else { return nil }
            let value = id.uint32Value
            return ReceiverDisplaySnapshot(id: value, visibleFrame: screen.visibleFrame,
                isPrimary: value == CGMainDisplayID(), isMirrored: CGDisplayIsInMirrorSet(value) != 0,
                isActive: CGDisplayIsActive(value) != 0)
        }
        let style: NSWindow.StyleMask = [.titled, .closable, .nonactivatingPanel]
        return try ReceiverTestPlacement.frame(displayID: displayID, displays: displays,
            preferredSize: NSWindow.frameRect(forContentRect: NSRect(x: 0, y: 0, width: 470, height: 700), styleMask: style).size,
            minimumSize: NSWindow.frameRect(forContentRect: NSRect(x: 0, y: 0, width: 380, height: 440), styleMask: style).size)
    }
}

private enum Palette {
    static let background = Color(red: 17 / 255, green: 25 / 255, blue: 27 / 255)
    static let panel = Color(red: 30 / 255, green: 42 / 255, blue: 44 / 255)
    static let border = Color(red: 51 / 255, green: 67 / 255, blue: 70 / 255)
    static let mint = Color(red: 152 / 255, green: 233 / 255, blue: 196 / 255)
    static let text = Color(red: 240 / 255, green: 245 / 255, blue: 243 / 255)
    static let muted = Color(red: 168 / 255, green: 186 / 255, blue: 183 / 255)
    static let warning = Color(red: 232 / 255, green: 192 / 255, blue: 138 / 255)
}

/// The live adapter owns no input or connection behavior. The same content can be
/// rendered offscreen with explicit, non-sensitive fixture values for layout QA.
struct ReceiverView: View {
    @ObservedObject var receiver: ReceiverController
    @ObservedObject var loginItem: ReceiverLoginItem

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ReceiverContent(
                    state: receiver.state,
                    selectedPairing: receiver.selectedPairing,
                    selectedHost: $receiver.selectedHost,
                    failure: receiver.failure,
                    isStarting: receiver.isStarting,
                    isStopping: receiver.isStopping,
                    copied: receiver.copied,
                    accessRequested: receiver.accessRequested,
                    accessNotice: receiver.accessNotice,
                    refresh: receiver.refresh,
                    requestAccess: receiver.requestAccess,
                    openAccessibilitySettings: receiver.openAccessibilitySettings,
                    copyPairing: receiver.copyPairing,
                    quit: { NSApp.terminate(nil) }
                )
                if loginItem.isAvailable {
                    Group {
                        if loginItem.needsFirstLaunchConsent {
                            ReceiverLoginConsentContent(requested: $loginItem.firstLaunchRequested,
                                                        confirm: loginItem.confirmFirstLaunch)
                        } else {
                            ReceiverLoginContent(
                                requested: Binding(get: { loginItem.isRequested }, set: loginItem.setRequested),
                                status: loginItem.status, failure: loginItem.failure,
                                openSettings: loginItem.openSystemSettings
                            )
                        }
                    }
                    .padding(.horizontal, 28)
                    .padding(.bottom, 20)
                    .frame(maxWidth: 530)
                }
            }
        }
        .background(Palette.background)
    }
}

struct ReceiverLoginConsentContent: View {
    @Binding var requested: Bool
    let confirm: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(NSLocalizedString("Launch at login", comment: ""), isOn: $requested)
                .toggleStyle(.checkbox)
            Text(NSLocalizedString("HostTap Remote can be ready for your phone when you log in to your Mac. You can turn this off here at any time.", comment: ""))
                .foregroundStyle(Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
            Button(NSLocalizedString("Confirm", comment: ""), action: confirm)
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
        }
        .font(.system(size: 12))
        .foregroundStyle(Palette.text)
        .tint(Palette.mint)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ReceiverLoginContent: View {
    @Binding var requested: Bool
    let status: ReceiverLoginStatus
    let failure: String?
    let openSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(NSLocalizedString("Launch at login", comment: ""), isOn: $requested)
                .toggleStyle(.checkbox)
                .help(NSLocalizedString("Run automatically when you log in. HostTap Remote keeps receiving phone input when the window is closed. Choose Quit to stop it.", comment: ""))
            if status == .requiresApproval {
                Text(NSLocalizedString("Waiting for system approval. Automatic launch is currently disabled.", comment: ""))
                    .foregroundStyle(Palette.warning)
            } else if status == .unavailable {
                Text(NSLocalizedString("Login item unavailable. Move HostTap Remote to Applications, then enable it again.", comment: ""))
                    .foregroundStyle(Palette.warning)
            }
            if let failure { Text(failure).foregroundStyle(Palette.warning) }
            if status == .requiresApproval || status == .unavailable || failure != nil {
                Button(action: openSettings) {
                    Text(NSLocalizedString("Open Login Items Settings", comment: "")).underline()
                }
                .buttonStyle(.plain)
                .foregroundStyle(Palette.mint)
            }
        }
        .font(.system(size: 12))
        .fixedSize(horizontal: false, vertical: true)
        .foregroundStyle(Palette.text)
        .tint(Palette.mint)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ReceiverContent: View {
    let state: ReceiverState?
    let selectedPairing: ReceiverPairing?
    @Binding var selectedHost: String?
    let failure: String?
    let isStarting: Bool
    let isStopping: Bool
    let copied: Bool
    let accessRequested: Bool
    let accessNotice: String?
    let refresh: () -> Void
    let requestAccess: () -> Void
    let openAccessibilitySettings: () -> Void
    let copyPairing: () -> Void
    let quit: () -> Void
    @FocusState private var focusedAction: Action?

    private enum Action: Hashable { case copy, requestAccess, settings, refresh, quit }

    var body: some View {
        VStack(spacing: 18) {
            statusPill
            heading
            notices
            #if SOFATAP_MAC_APP_STORE
            Text(NSLocalizedString("The basic App Store version supports mouse control, scrolling and sending text. Automatic text-field detection and live editing are unavailable.", comment: ""))
                .font(.system(size: 12))
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            #endif
            if let state, failure == nil, !isStarting, !isStopping {
                pairingCard(state)
            }
            footer
            actions
        }
        .padding(.horizontal, 28)
        .padding(.top, 22)
        .padding(.bottom, 20)
        .frame(maxWidth: 530)
        .frame(maxWidth: .infinity)
        .background(Palette.background)
        .foregroundStyle(Palette.text)
        .tint(Palette.mint)
        .preferredColorScheme(.dark)
    }

    private var heading: some View {
        VStack(spacing: 8) {
            Text(NSLocalizedString("Open the companion app on your phone and scan the QR code", comment: ""))
                .font(.system(size: 14))
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var statusTitle: String {
        if isStopping { return NSLocalizedString("Quitting", comment: "") }
        if failure != nil { return NSLocalizedString("Receiver unavailable", comment: "") }
        if isStarting { return NSLocalizedString("Preparing receiver", comment: "") }
        guard let state else { return NSLocalizedString("Waiting for receiver status", comment: "") }
        let connection = state.connected ? NSLocalizedString("Phone connected", comment: "") : NSLocalizedString("Waiting for phone (iOS/Android)", comment: "")
        if state.dryRun || state.inputStatus == "dry_run" { return String(format: NSLocalizedString("Dry run · %@", comment: ""), connection) }
        if state.inputStatus == "accessibility_required" {
            return state.connected ? NSLocalizedString("Phone connected · Permission needed", comment: "") : NSLocalizedString("Accessibility permission needed", comment: "")
        }
        guard state.inputStatus == "ready" else {
            return state.connected ? NSLocalizedString("Phone connected · Desktop unavailable", comment: "") : NSLocalizedString("Mac desktop unavailable", comment: "")
        }
        if !state.connected && state.pairings.isEmpty { return NSLocalizedString("No pairing address available", comment: "") }
        return state.connected ? NSLocalizedString("Phone connected · Desktop ready", comment: "") : NSLocalizedString("Ready · Waiting for phone (iOS/Android)", comment: "")
    }

    private var statusColor: Color {
        if isStarting || isStopping { return Palette.muted }
        guard failure == nil, let state else { return Palette.warning }
        return !state.dryRun && state.inputStatus == "ready" && (state.connected || !state.pairings.isEmpty) ? Palette.mint : Palette.warning
    }

    private var statusPill: some View {
        HStack(spacing: 8) {
            if isStarting || isStopping {
                Image(systemName: "hourglass").font(.system(size: 11)).foregroundStyle(Palette.muted).accessibilityHidden(true)
            } else {
                Circle().fill(statusColor).frame(width: 7, height: 7).accessibilityHidden(true)
            }
            Text(statusTitle)
                .font(.system(size: 12, weight: .medium))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .background(Palette.panel, in: Capsule())
        .overlay(Capsule().stroke(Palette.border, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var notices: some View {
        if isStopping {
            notice(icon: "hourglass", title: NSLocalizedString("Disconnecting", comment: ""), message: NSLocalizedString("Disconnecting the phone and stopping the receiver.", comment: ""), color: Palette.muted)
        } else if let failure {
            notice(icon: "exclamationmark.triangle", title: NSLocalizedString("Unable to connect", comment: ""), message: failure, color: Palette.warning)
        } else if isStarting {
            notice(icon: "desktopcomputer", title: NSLocalizedString("Preparing Mac receiver", comment: ""), message: NSLocalizedString("The QR code will appear here when the receiver is ready.", comment: ""), color: Palette.muted)
        } else if let state {
            if state.dryRun || state.inputStatus == "dry_run" {
                notice(icon: "testtube.2", title: NSLocalizedString("Dry run", comment: ""), message: NSLocalizedString("You can check the connection, but no mouse or keyboard input will be sent to the Mac.", comment: ""), color: Palette.warning)
            } else if state.inputStatus == "accessibility_required" {
                permissionNotice
            } else if state.inputStatus != "ready" {
                notice(icon: "lock.display", title: NSLocalizedString("Mac desktop unavailable", comment: ""), message: NSLocalizedString("Log in and unlock this Mac, then refresh.", comment: ""), color: Palette.warning)
            }
        }
    }

    private var permissionNotice: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(NSLocalizedString("Allow HostTap Remote to control your Mac", comment: ""), systemImage: "hand.raised")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.text)
            Text(accessNotice ?? NSLocalizedString("Accessibility permission is required to control the mouse and type text. Allow HostTap Remote in the system prompt.", comment: ""))
                .font(.system(size: 12))
                .foregroundStyle(Palette.muted)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { permissionButtons }
                VStack(alignment: .leading, spacing: 8) { permissionButtons }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.border, lineWidth: 1))
    }

    @ViewBuilder private var permissionButtons: some View {
        Button(accessRequested ? NSLocalizedString("Request permission again", comment: "") : NSLocalizedString("Request Accessibility Permission", comment: ""), action: requestAccess)
            .buttonStyle(MintButtonStyle(focused: focusedAction == .requestAccess))
            .focused($focusedAction, equals: .requestAccess)
        Button(NSLocalizedString("Open System Settings", comment: ""), action: openAccessibilitySettings)
            .buttonStyle(QuietButtonStyle(focused: focusedAction == .settings))
            .focused($focusedAction, equals: .settings)
    }

    private func pairingCard(_ state: ReceiverState) -> some View {
        VStack(spacing: 16) {
            if let pairing = selectedPairing, let data = pairing.pngData, let image = ReceiverQRCode.decode(data) {
                // Preserve every module and the complete QR quiet zone. No brand mark,
                // caption or decorative element is painted over the pairing code.
                ReceiverQRCode(image: image)
                    .padding(10)
                    .background(.white, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(NSLocalizedString("Scan this QR code with the phone companion app to pair your Mac", comment: ""))
                deviceAndNetwork(state, pairing: pairing)
                Button(action: copyPairing) {
                    Label(copied ? NSLocalizedString("Pairing link copied", comment: "") : NSLocalizedString("Copy Pairing Link", comment: ""), systemImage: copied ? "checkmark" : "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(MintButtonStyle(focused: focusedAction == .copy))
                .focused($focusedAction, equals: .copy)
                .disabled(isStopping || failure != nil)
            } else {
                Image(systemName: "wifi.slash")
                    .font(.system(size: 28)).foregroundStyle(Palette.muted)
                    .padding(.top, 16).accessibilityHidden(true)
                Text(state.pairings.isEmpty ? NSLocalizedString("No network address available for pairing", comment: "") : NSLocalizedString("Unable to display QR code", comment: ""))
                    .font(.system(size: 16, weight: .semibold))
                Text(state.pairings.isEmpty ? NSLocalizedString("Connect to Wi-Fi or Ethernet, then click Refresh below.", comment: "") : NSLocalizedString("Click Refresh below to get a new pairing QR code.", comment: ""))
                    .font(.system(size: 13)).foregroundStyle(Palette.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 16)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Palette.border, lineWidth: 1))
    }

    private func deviceAndNetwork(_ state: ReceiverState, pairing: ReceiverPairing) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Label {
                Text(state.name).lineLimit(2).truncationMode(.middle)
            } icon: {
                Image(systemName: "laptopcomputer").font(.system(size: 18))
            }
            .font(.system(size: 13, weight: .medium))
            .frame(maxWidth: .infinity, alignment: .leading)
            .help(state.name)
            Rectangle().fill(Palette.border).frame(width: 1, height: 30)
            VStack(alignment: .leading, spacing: 3) {
                if state.pairings.count > 1 {
                    Picker(NSLocalizedString("Network address", comment: ""), selection: $selectedHost) {
                        ForEach(state.pairings) { address in
                            Text(address.host).tag(Optional(address.host))
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .controlSize(.small)
                    .help(NSLocalizedString("If multiple networks are available, choose an address your phone can reach.", comment: ""))
                    .accessibilityLabel(NSLocalizedString("Network address: choose an address your phone can reach", comment: ""))
                } else {
                    Label(NSLocalizedString("Network address", comment: ""), systemImage: "network")
                        .font(.system(size: 11)).foregroundStyle(Palette.muted)
                    Text(pairing.host)
                        .font(.system(size: 12, design: .monospaced))
                        .lineLimit(2).truncationMode(.middle)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .help(pairing.host)
        }
        .frame(minHeight: 34)
    }

    private func notice(icon: String, title: String, message: String, color: Color) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).font(.system(size: 17)).foregroundStyle(color).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 14, weight: .semibold))
                Text(message).font(.system(size: 12)).foregroundStyle(Palette.muted)
                    .lineSpacing(3).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.border, lineWidth: 1))
    }

    private var footer: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 14) {
                Label(NSLocalizedString("Connect phone and Mac to the same network", comment: ""), systemImage: "info.circle")
                Label(NSLocalizedString("Keep your pairing code private", comment: ""), systemImage: "lock.shield")
            }
            VStack(spacing: 7) {
                Label(NSLocalizedString("Connect phone and Mac to the same network", comment: ""), systemImage: "info.circle")
                Label(NSLocalizedString("Keep your pairing code private", comment: ""), systemImage: "lock.shield")
            }
        }
        .font(.system(size: 11))
        .foregroundStyle(Palette.muted)
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .help(NSLocalizedString("The QR code and pairing link grant control of this Mac. Keep them private.", comment: ""))
        .accessibilityLabel(NSLocalizedString("Connect phone and Mac to the same network. The QR code and pairing link grant control of this Mac. Keep them private.", comment: ""))
    }

    private var actions: some View {
        HStack(spacing: 24) {
            Button(action: refresh) { Label(NSLocalizedString("Refresh", comment: ""), systemImage: "arrow.clockwise") }
                .buttonStyle(QuietButtonStyle(focused: focusedAction == .refresh))
                .focused($focusedAction, equals: .refresh)
                .disabled(isStarting || isStopping)
            Rectangle().fill(Palette.border).frame(width: 1, height: 22)
            Button(action: quit) { Label(NSLocalizedString("Quit", comment: ""), systemImage: "power") }
                .buttonStyle(QuietButtonStyle(focused: focusedAction == .quit))
                .focused($focusedAction, equals: .quit)
                .keyboardShortcut("q", modifiers: .command)
                .disabled(isStopping)
        }
    }
}

/// Decode the source pixels directly. NSImage may rasterize a small representation
/// with interpolation before SwiftUI sees it, even when Image uses `.none`.
/// Keeping CGImage pixels intact makes the production and fixture paths identical.
struct ReceiverQRCode: View {
    let image: CGImage

    var body: some View {
        Image(decorative: image, scale: 1)
            .resizable()
            .interpolation(.none)
            .scaledToFit()
            .frame(width: 224, height: 224)
    }

    static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}

private struct MintButtonStyle: ButtonStyle {
    var focused = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Palette.background)
            .padding(.horizontal, 14)
            .frame(minHeight: 42)
            .background(Palette.mint.opacity(isEnabled ? (configuration.isPressed ? 0.82 : 1) : 0.42), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(focused && isEnabled ? Palette.text : .clear, lineWidth: 2).padding(-3))
            .contentShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct QuietButtonStyle: ButtonStyle {
    var focused = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(Palette.text.opacity(isEnabled ? 1 : 0.4))
            .padding(.horizontal, 8)
            .frame(minHeight: 32)
            .background(configuration.isPressed ? Palette.border.opacity(0.5) : .clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(focused && isEnabled ? Palette.mint : .clear, lineWidth: 2).padding(-2))
            .contentShape(RoundedRectangle(cornerRadius: 8))
    }
}
