import AppKit
#if SOFATAP_MAC_APP_STORE
import CoreGraphics
#else
@preconcurrency import ApplicationServices
#endif
import Combine
import Darwin
import Foundation

/// All UI state lives on the main actor. Pipe readers and the writer run independently of AppKit.
@MainActor
final class ReceiverController: ObservableObject {
    @Published private(set) var state: ReceiverState?
    @Published var selectedHost: String?
    @Published private(set) var failure: String?
    @Published private(set) var isStarting = true
    @Published private(set) var isStopping = false
    @Published private(set) var copied = false
    @Published private(set) var accessRequested = false
    @Published private(set) var accessNotice: String?

    private var process: Process?
    private var generation = UUID()
    private var pipes: [Pipe] = []
    private var pipeReaders: [ReceiverPipeReader] = []
    private var stateReader: Task<Void, Never>?
    private var writer: Task<Void, Never>?
    private var commandStream: AsyncStream<ReceiverCommand>.Continuation?
    private var watchdog: Task<Void, Never>?
    private var stopTask: Task<Void, Never>?
    private var copyTask: Task<Void, Never>?
    private var stopCompletion: ((Bool) -> Void)?

    var selectedPairing: ReceiverPairing? {
        state?.pairings.first { $0.host == selectedHost }
    }

    func start() {
        guard process == nil, !isStopping else { return }
        let id = UUID()
        generation = id
        state = nil
        selectedHost = nil
        failure = nil
        isStarting = true
        copied = false
        accessRequested = false
        accessNotice = nil
        #if SOFATAP_MAC_APP_STORE
        #if arch(arm64)
        let helperPath = "Contents/Helpers/arm64/SofaTapHost"
        #elseif arch(x86_64)
        let helperPath = "Contents/Helpers/x64/SofaTapHost"
        #else
        #error("Unsupported Mac App Store helper architecture")
        #endif
        #else
        let helperPath = "Contents/Helpers/SofaTapHost"
        #endif
        let helper = Bundle.main.bundleURL.appendingPathComponent(helperPath)
        guard FileManager.default.isExecutableFile(atPath: helper.path) else {
            fail(NSLocalizedString("Mac receiver component not found. Reinstall HostTap Remote.", comment: ""))
            return
        }

        let child = Process()
        let input = Pipe(), output = Pipe(), errors = Pipe()
        child.executableURL = helper
        child.arguments = ["--native-ui"] + (CommandLine.arguments.contains("--dry-run") ? ["--dry-run"] : [])
        #if SOFATAP_MAC_APP_STORE
        do {
            let directories = try ReceiverStoreDirectories.production()
            child.arguments?.append(contentsOf: ["--data-dir", directories.data.path])
            child.environment = directories.helperEnvironment(inherited: ProcessInfo.processInfo.environment)
        } catch {
            fail(NSLocalizedString("Unable to open HostTap Remote app storage. Make sure you are using the complete Mac App Store app.", comment: ""))
            return
        }
        #endif
        child.standardInput = input
        child.standardOutput = output
        child.standardError = errors
        // Writing after a child exit must throw EPIPE instead of terminating the UI with SIGPIPE.
        guard fcntl(input.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1) != -1 else {
            fail(NSLocalizedString("Unable to connect to the receiver component. Quit and reopen HostTap Remote.", comment: ""))
            return
        }
        child.terminationHandler = { [weak self] _ in
            Task { @MainActor in self?.didExit(id: id) }
        }
        process = child
        pipes = [input, output, errors]
        do {
            try child.run()
        } catch {
            cleanUp()
            fail(NSLocalizedString("Unable to start the Mac receiver. Check the installation and whether another copy of HostTap Remote is running.", comment: ""))
            return
        }
        // Only the child owns these ends after launch; close our copies so EOF is observable.
        try? input.fileHandleForReading.close()
        try? output.fileHandleForWriting.close()
        try? errors.fileHandleForWriting.close()

        let (commands, continuation) = AsyncStream<ReceiverCommand>.makeStream(bufferingPolicy: .bufferingOldest(16))
        commandStream = continuation
        let stdin = input.fileHandleForWriting
        writer = Task.detached { [weak self] in
            do {
                for await command in commands {
                    if Task.isCancelled { return }
                    try stdin.write(contentsOf: command.line)
                }
            } catch {
                await self?.pipeFailed(id: id)
            }
        }
        do {
            let stdout = try ReceiverPipeReader(handle: output.fileHandleForReading, parsesStates: true)
            let stderr = try ReceiverPipeReader(handle: errors.fileHandleForReading, parsesStates: false)
            pipeReaders = [stdout, stderr]
            stateReader = Task { [weak self] in
                for await event in stdout.events {
                    guard !Task.isCancelled else { return }
                    switch event {
                    case .state(let update): self?.receive(update, id: id)
                    case .ended: self?.pipeFailed(id: id)
                    }
                }
            }
        } catch {
            pipeFailed(id: id)
            return
        }
        scheduleWatchdog(id: id)
    }

    func refresh() {
        if process == nil { start() } else { send(.refresh) }
    }

    func requestAccess() {
        guard state?.inputStatus == "accessibility_required", state?.dryRun == false, !isStopping else { return }
        // Ask from the user-visible, signed .app, so macOS can identify Sofatap rather
        // than only its helper. Apple documents this prompt as asynchronous: the
        // return value is current trust, not the user's response or helper readiness.
        #if SOFATAP_MAC_APP_STORE
        // App Sandbox cannot use AXUIElement. Basic control asks only for event
        // posting access from an explicit native button click. A granted parent
        // preflight does not prove that the bundled helper can inject events.
        if !CGPreflightPostEventAccess() { _ = CGRequestPostEventAccess() }
        #else
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        #endif
        accessRequested = true
        accessNotice = NSLocalizedString("Open Settings from the system prompt and allow HostTap Remote. If no prompt appears, click Open System Settings. If the app is missing from the list, click + and select the installed HostTap Remote.app.", comment: "")
        // Leave the asynchronous system prompt in front. Do not concurrently ask
        // the helper to open Settings. Its periodic permission preflight remains the
        // authority for whether real input is available.
        send(.refresh)
    }

    func openAccessibilitySettings() {
        guard state?.inputStatus == "accessibility_required", state?.dryRun == false, !isStopping else { return }
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!
        if !NSWorkspace.shared.open(url) {
            accessNotice = NSLocalizedString("Open System Settings → Privacy & Security → Accessibility and allow HostTap Remote, then return here and refresh.", comment: "")
        }
    }

    func copyPairing() {
        guard let pairing = selectedPairing, !isStopping else { return }
        NSPasteboard.general.clearContents()
        guard NSPasteboard.general.setString(pairing.uri, forType: .string) else { return }
        copied = true
        copyTask?.cancel()
        copyTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.copied = false
        }
    }

    /// Never terminate an unrelated process. If this child does not exit, keep the UI alive to retry.
    func stop(completion: @escaping (Bool) -> Void) {
        guard !isStopping else { return }
        guard let child = process, child.isRunning else { cleanUp(); completion(true); return }
        isStopping = true
        stopCompletion = completion
        state = nil
        selectedHost = nil
        watchdog?.cancel()
        send(.quit)
        stopTask = Task { [weak self, child] in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled, let self, self.isStopping else { return }
            if child.isRunning { child.terminate() }
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, self.isStopping else { return }
            if child.isRunning {
                self.isStopping = false
                self.fail(NSLocalizedString("The receiver component has not stopped yet. Try Quit HostTap Remote again shortly.", comment: ""))
                let callback = self.stopCompletion
                self.stopCompletion = nil
                callback?(false)
            } else { self.didExit(id: self.generation) }
        }
    }

    private func send(_ command: ReceiverCommand) {
        guard let commandStream else { return }
        if case .dropped = commandStream.yield(command) {
            fail(NSLocalizedString("The receiver component is not responding. Try refreshing shortly.", comment: ""))
        }
    }

    private func receive(_ update: ReceiverState, id: UUID) {
        guard id == generation, process?.isRunning == true, !isStopping else { return }
        selectedHost = update.selectedHost(preserving: selectedHost)
        state = update
        failure = nil
        isStarting = false
        scheduleWatchdog(id: id)
    }

    private func scheduleWatchdog(id: UUID) {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            guard !Task.isCancelled, let self, self.generation == id, !self.isStopping else { return }
            self.fail(NSLocalizedString("The receiver component did not respond in time. Refresh, or quit and reopen HostTap Remote.", comment: ""))
        }
    }

    private func pipeFailed(id: UUID) {
        guard id == generation, process != nil, !isStopping else { return }
        fail(NSLocalizedString("Connection to the Mac receiver component was lost. Reopen HostTap Remote.", comment: ""))
        if process?.isRunning == true { process?.terminate() }
    }

    private func didExit(id: UUID) {
        guard id == generation, process != nil else { return }
        let wasStopping = isStopping
        let callback = stopCompletion
        stopCompletion = nil
        cleanUp()
        isStopping = false
        if wasStopping { callback?(true) }
        else { fail(failure ?? NSLocalizedString("The Mac receiver component has stopped. Refresh, or quit and reopen HostTap Remote.", comment: "")) }
    }

    private func fail(_ message: String) {
        state = nil
        selectedHost = nil
        copied = false
        isStarting = false
        failure = message
    }

    private func cleanUp() {
        generation = UUID()
        watchdog?.cancel()
        stopTask?.cancel()
        copyTask?.cancel()
        commandStream?.finish()
        commandStream = nil
        writer?.cancel()
        writer = nil
        stateReader?.cancel()
        stateReader = nil
        pipeReaders.forEach { $0.cancel() }
        pipeReaders.removeAll()
        pipes.forEach {
            try? $0.fileHandleForReading.close()
            try? $0.fileHandleForWriting.close()
        }
        pipes.removeAll()
        process?.terminationHandler = nil
        process = nil
    }
}
