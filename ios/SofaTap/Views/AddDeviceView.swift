import SwiftUI
import AVFoundation

/// A single entry point for computer scans, pasted codes and system pairing links.
struct AddDeviceView: View {
    @ObservedObject var model: ControllerModel
    @ObservedObject var tv: WebOSTVController
    let selectTV: () -> Void
    let selectComputer: () -> Void
    @Binding var incomingLink: String?
    let finishPairing: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var attempt = PairingAttemptState()
    @State private var addedDevice: AddedDevice?
    @State private var draftName = ""
    @State private var error: String?
    @State private var cameraError: String?
    @State private var cameraID = UUID()
    @State private var showPaste = false
    @State private var pastedLink = ""
    @State private var showWebOS = false

    private struct AddedDevice {
        enum Kind { case computer, webos, demo }
        let id: UUID
        let kind: Kind
        let name: String
        let platform: String
    }
    private var candidate: PairingCode? { attempt.candidate }
    private var cameraActive: Bool {
        candidate == nil && addedDevice == nil && !showPaste && !showWebOS && scenePhase == .active
    }
    private var demo: Bool {
#if targetEnvironment(simulator)
        return model.demo
#else
        return false
#endif
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        if let addedDevice { naming(addedDevice) }
                        else if let candidate { connection(candidate) }
                        else { scanPage(cameraHeight: max(210, min(340, geometry.size.height * 0.47))) }
                    }.padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 24)
                }.scrollDismissesKeyboard(.interactively)
            }
            .background(Palette.background)
            .navigationTitle(addedDevice == nil ? String(localized: "扫描二维码") : String(localized: "设备已添加"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: addedDevice == nil ? .cancellationAction : .confirmationAction) {
                    Button(addedDevice == nil ? String(localized: "取消") : String(localized: "完成")) {
                        if let addedDevice { saveName(addedDevice) }
                        else { cancelPending(); dismiss() }
                    }.disabled(addedDevice != nil && draftName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationDestination(isPresented: $showWebOS) {
                TVPairingView(tv: tv, selectTV: selectTV, finishPairing: finishWebOS,
                              completionTitle: String(localized: "继续"))
            }
            .sheet(isPresented: $showPaste) { pasteSheet }
            .onAppear {
                consumeIncomingLink()
#if targetEnvironment(simulator)
                if model.demo && ProcessInfo.processInfo.arguments.contains("--demo-added-device") {
                    let fixture = AddedDevice(id: UUID(), kind: .demo, name: "Living room Mac", platform: "Mac")
                    addedDevice = fixture; draftName = fixture.name
                }
#endif
            }
            .onChange(of: incomingLink) { _, _ in consumeIncomingLink() }
            .onChange(of: model.connected) { _, _ in completeIfConnected() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active && candidate == nil && addedDevice == nil {
                    cameraError = nil; cameraID = UUID()
                }
            }
            .onDisappear { cancelPending() }
        }.tint(Palette.mint).preferredColorScheme(.dark)
    }

    @ViewBuilder private func scanPage(cameraHeight: CGFloat) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Palette.panel
                if demo {
                    Image(systemName: "qrcode.viewfinder").font(.system(size: 72, weight: .ultraLight))
                        .foregroundStyle(Palette.mint.opacity(0.25))
                    VStack { Spacer(); Text(String(localized: "相机预览（演示）"))
                        .font(.caption).foregroundStyle(Palette.muted).padding(14) }
                } else {
                    QRScanner(isActive: cameraActive,
                              accepts: { attempt.canBegin($0) },
                              onScan: accept, onError: { cameraError = $0 })
                        .id(cameraID)
                        .accessibilityLabel(String(localized: "扫描二维码"))
                }
                ScanCorners()
                    .stroke(Palette.mint.opacity(0.7), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                    .frame(width: 180, height: 180).allowsHitTesting(false).accessibilityHidden(true)
                if let cameraError {
                    VStack(spacing: 12) {
                        Text(cameraError).font(.footnote).multilineTextAlignment(.center)
                        if AVCaptureDevice.authorizationStatus(for: .video) == .denied ||
                            AVCaptureDevice.authorizationStatus(for: .video) == .restricted {
                            Button(String(localized: "打开系统设置")) {
                                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                            }.buttonStyle(.bordered)
                        }
                    }.padding(20).background(Palette.background.opacity(0.94), in: RoundedRectangle(cornerRadius: 16))
                        .padding(16)
                }
            }
            .frame(height: cameraHeight).clipShape(RoundedRectangle(cornerRadius: 26))
            .accessibilityIdentifier("pairingCameraRegion")
            Text(String(localized: "将 Sofatap 配对二维码放入画面"))
                .font(.subheadline.weight(.medium)).multilineTextAlignment(.center)
            Text(String(localized: "扫描后自动识别电脑，无需选择设备类型。"))
                .font(.footnote).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
        }
        if let error { Text(error).font(.footnote).foregroundStyle(.orange).accessibilityIdentifier("pairingError") }
        supportedDevices
        Button { pastedLink = ""; showPaste = true } label: {
            Label(String(localized: "粘贴配对链接"), systemImage: "link")
                .font(.subheadline).frame(maxWidth: .infinity, minHeight: 44)
        }.buttonStyle(RemoteIconButtonStyle()).accessibilityIdentifier("pastePairingLink")
    }

    private var supportedDevices: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(String(localized: "支持的设备")).font(.headline)
            HStack(spacing: 0) {
                platformBadge("Mac", symbol: "laptopcomputer")
                platformBadge("Windows", symbol: "desktopcomputer")
                platformBadge("Linux", symbol: "terminal")
            }
            Text(String(localized: "Mac、Windows 和 Linux 需先安装 Sofatap 接收器。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            Divider().overlay(Palette.muted.opacity(0.12))
            HStack {
                Text(verbatim: "LG webOS").font(.subheadline.weight(.medium))
                Spacer()
                Button(String(localized: "手动连接 LG webOS")) { showWebOS = true }
                    .font(.caption).frame(minHeight: 44)
            }
            Text(String(localized: "LG webOS 使用电视授权配对，暂不支持扫码。"))
                .font(.footnote).foregroundStyle(Palette.muted)
        }.accessibilityIdentifier("supportedPairingDevices")
    }
    private func platformBadge(_ name: String, symbol: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: symbol).font(.system(size: 24)).foregroundStyle(Palette.mint)
            Text(verbatim: name).font(.subheadline.weight(.medium))
        }.frame(maxWidth: .infinity).padding(.vertical, 8)
    }

    private var pasteSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                Text(String(localized: "或粘贴完整配对链接")).foregroundStyle(Palette.muted)
                TextField("sofatap://…", text: $pastedLink, axis: .vertical)
                    .lineLimit(3...6).font(.caption.monospaced()).textInputAutocapitalization(.never)
                    .autocorrectionDisabled().privacySensitive()
                    .padding(16).background(Palette.panel, in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityIdentifier("pairingLink")
                Button(String(localized: "安全保存并连接")) {
                    let value = pastedLink; pastedLink = ""; showPaste = false; accept(value)
                }.buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
                    .disabled(pastedLink.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Spacer(minLength: 0)
            }.padding(20).background(Palette.background)
                .navigationTitle(String(localized: "粘贴配对链接")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "取消")) { pastedLink = ""; showPaste = false }
                } }
        }.presentationDetents([.medium, .large])
    }

    private func connection(_ code: PairingCode) -> some View {
        let failed = connectionFailed(code)
        return VStack(alignment: .leading, spacing: 20) {
            Image(systemName: failed ? "exclamationmark.circle" : "checkmark.viewfinder")
                .font(.system(size: 42)).foregroundStyle(failed ? .orange : Palette.mint)
            Text(failed ? String(localized: "连接未完成") : String(localized: "正在添加设备…"))
                .font(.title2.weight(.semibold))
            Label(String(localized: "已自动识别"), systemImage: "checkmark.circle").font(.caption).foregroundStyle(Palette.muted)
            Text(candidateName(code)).font(.headline)
            Text(candidatePlatform(code)).font(.subheadline).foregroundStyle(Palette.mint)
            if !failed { ProgressView().tint(Palette.mint) }
            Text(connectionNotice(code)).font(.subheadline).foregroundStyle(failed ? .orange : Palette.muted)
            if failed, case .computer = code {
                Button(String(localized: "重试连接")) { retryComputer() }
                    .buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
            }
            if let error { Text(error).font(.footnote).foregroundStyle(.orange) }
            Button(String(localized: "再次扫描")) { resetScanner() }
                .frame(minHeight: 44)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 22)
    }

    private func naming(_ device: AddedDevice) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 48)).foregroundStyle(Palette.mint)
            Text(String(localized: "给设备起个名字")).font(.title2.weight(.semibold))
            Text(verbatim: device.platform).font(.subheadline).foregroundStyle(Palette.muted)
            TextField(String(localized: "设备名称"), text: $draftName)
                .font(.title3).submitLabel(.done).autocorrectionDisabled()
                .padding(16).background(Palette.panel, in: RoundedRectangle(cornerRadius: 16))
                .accessibilityIdentifier("newDeviceName").onSubmit { saveName(device) }
            Text(String(localized: "以后可在“我的设备”中修改名称。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            Text(String(localized: "名称只在这台 iPhone 上更改。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            if let error { Text(error).font(.footnote).foregroundStyle(.orange) }
            Button(String(localized: "保存并完成")) { saveName(device) }
                .frame(maxWidth: .infinity, minHeight: 44).buttonStyle(.borderedProminent).foregroundStyle(Palette.background)
                .disabled(draftName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("saveNewDeviceName")
            Button(String(localized: "保留原名称")) { finishPairing() }
                .frame(maxWidth: .infinity, minHeight: 44)
        }.padding(.vertical, 24).accessibilityIdentifier("addedDeviceNaming")
    }

    private func consumeIncomingLink() {
        guard let raw = incomingLink else { return }
        incomingLink = nil; accept(raw)
    }
    private func accept(_ raw: String) {
        // An unrelated QR must not alter selection or cancel a working connection.
        guard !demo else { return }
        var nextAttempt = attempt
        do {
            guard try nextAttempt.begin(raw) else { return }
        } catch {
            self.error = String(localized: "这不是有效的 Sofatap 配对二维码或链接。请在接收器中重新生成。")
            return
        }
        guard let code = nextAttempt.candidate else { return }
        cancelPending()
        attempt = nextAttempt
        cameraError = nil; error = nil; addedDevice = nil
        showPaste = false; pastedLink = ""; showWebOS = false
        switch code {
        case .computer: selectComputer(); model.acceptPairing(raw)
        }
        completeIfConnected()
    }
    private func retryComputer() {
        guard addedDevice == nil, let raw = attempt.computerRetryLink else { return }
        error = nil
        // A failed save leaves the previous stored pairing selected. Retrying
        // must save this candidate again, never connect that previous device.
        selectComputer(); model.acceptPairing(raw)
        completeIfConnected()
    }
    private func completeIfConnected() {
        guard let candidate, addedDevice == nil else { return }
        switch candidate {
        case .computer(let pairing):
            guard model.connected, model.pairing == pairing,
                  let id = model.selectedDeviceID else { return }
            setAdded(AddedDevice(id: id, kind: .computer, name: model.currentDisplayName,
                                platform: candidatePlatform(candidate)))
        }
    }
    private func finishWebOS() {
        guard tv.phase == .ready, let id = tv.selectedDeviceID else { return }
        showWebOS = false
        setAdded(AddedDevice(id: id, kind: .webos, name: tv.currentDisplayName, platform: "LG webOS"))
    }
    private func setAdded(_ device: AddedDevice) {
        attempt.markAuthenticated()
        addedDevice = device; draftName = device.name; error = nil
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
    private func saveName(_ device: AddedDevice) {
        switch device.kind {
        case .computer: model.renameDevice(device.id, name: draftName); error = model.pairingError
        case .webos: tv.renameDevice(device.id, name: draftName); error = tv.managementError
        case .demo: error = nil
        }
        if error == nil { finishPairing() }
    }
    private func cancelPending() {
        guard addedDevice == nil, let candidate = attempt.pendingCandidate else { return }
        switch candidate {
        case .computer:
            // Includes timeout/background and failed-save states. None should
            // reconnect this attempt (or a previous stored target) on foreground.
            model.disconnectDevice()
        }
    }
    private func resetScanner() {
        cancelPending(); attempt.resetScanner(); error = nil; cameraError = nil; cameraID = UUID()
    }
    private func connectionFailed(_ code: PairingCode) -> Bool {
        model.pairingError != nil || (!model.connected && !model.connecting)
    }
    private func connectionNotice(_ code: PairingCode) -> String { model.pairingError ?? model.notice }
    private func candidateName(_ code: PairingCode) -> String {
        switch code {
        case .computer(let pairing): return pairing.name ?? pairing.host
        }
    }
    private func candidatePlatform(_ code: PairingCode) -> String {
        switch code {
        case .computer(let pairing):
            let platform = model.pairing?.deviceIdentity == pairing.deviceIdentity && model.connected
                ? model.savedDevices.first(where: { $0.id == model.selectedDeviceID })?.platform : pairing.platform
            switch platform { case "macos": return "Mac"; case "windows": return "Windows"; case "linux": return "Linux"; default: return String(localized: "电脑") }
        }
    }
}

private struct ScanCorners: Shape {
    func path(in rect: CGRect) -> Path {
        let length: CGFloat = 28
        var path = Path()
        for (x, y, dx, dy) in [(rect.minX, rect.minY, 1.0, 1.0), (rect.maxX, rect.minY, -1.0, 1.0),
                               (rect.minX, rect.maxY, 1.0, -1.0), (rect.maxX, rect.maxY, -1.0, -1.0)] {
            path.move(to: CGPoint(x: x + dx * length, y: y))
            path.addLine(to: CGPoint(x: x, y: y))
            path.addLine(to: CGPoint(x: x, y: y + dy * length))
        }
        return path
    }
}
