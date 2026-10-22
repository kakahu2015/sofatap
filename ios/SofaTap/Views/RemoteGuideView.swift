import SwiftUI

/// Illustrations never send receiver input. Help is separate from device setup.
struct RemoteGuideView: View {
    let configuration: GestureConfiguration
    let context: DeviceGuideContext
    @Environment(\.dismiss) private var dismiss
    @State private var showPractice = false

    private var target: GestureTarget { context.target }
    private var currentConfiguration: GestureConfiguration { context.configuration(configuration) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    deviceCard
                    if target == .television { televisionHelp }
                    mappingCard
                    Button { showPractice = true } label: {
                        Label(String(localized: "试试这些手势"), systemImage: "hand.draw")
                            .font(.headline).frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("guidePractice")
                    if context.showsTextInputHelp {
                        guideCard("keyboard", gesture: .text,
                                  title: String(localized: "键盘与语音输入"),
                                  detail: String(localized: "先选中电脑输入框，再点键盘或麦克风。支持的输入框自动同步，其他输入框用发送按钮提交。箭头可收起或展开键盘。"))
                        guideCard("delete.left", gesture: .backspace,
                                  title: String(localized: "草稿与电脑退格"),
                                  detail: String(localized: "先选中电脑输入框。实时输入时退格会同步；草稿中的未发送文字先在手机上删除。清空本地草稿后，可用键盘退格删除电脑文字。框下的电脑退格按钮直接作用于电脑，不删除手机草稿。"))
                    }
                    HStack {
                        Link(String(localized: "隐私政策"), destination: URL(string: "https://github.com/chekusu/sofatap/blob/main/PRIVACY.md")!)
                        Spacer()
                        Link(String(localized: "帮助与反馈"), destination: URL(string: "https://github.com/chekusu/sofatap/issues")!)
                    }.font(.footnote).padding(.vertical, 12)
                }.padding(20)
            }
            .background(Palette.background)
            .navigationTitle(String(localized: "使用指南"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(String(localized: "完成")) { dismiss() } } }
            .sheet(isPresented: $showPractice) {
                GesturePracticeView(configuration: currentConfiguration, target: target,
                                    availableActions: context.availableActions, guideContext: context)
            }
        }
        .tint(Palette.mint).preferredColorScheme(.dark)
    }

    private var deviceCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(target == .television ? String(localized: "电视操作指南") : String(localized: "电脑操作指南"),
                  systemImage: target == .television ? "tv" : "desktopcomputer")
                .font(.title2.weight(.semibold))
            Text(context.deviceName).font(.headline)
                .accessibilityIdentifier("guideDeviceName")
            HStack {
                Text(platformTitle)
                Spacer()
                Text(context.connected ? String(localized: "已连接") : String(localized: "未连接"))
            }.font(.footnote).foregroundStyle(Palette.muted)
            if !context.connected || context.availableActions == nil {
                Text(String(localized: "连接设备后确认可用操作"))
                    .font(.footnote).foregroundStyle(Palette.muted)
            }
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 20))
    }

    private var platformTitle: String {
        switch context.kind {
        case .computer: return String(localized: "电脑")
        case .windows: return "Windows"
        case .macos: return "macOS"
        case .linux: return "Linux"
        case .webOS: return "LG webOS"
        }
    }

    @ViewBuilder private var televisionHelp: some View {
        guideCard("cursorarrow", gesture: .tvFocus,
                  title: String(localized: "点击与确认"),
                  detail: String(localized: "LG webOS 使用电视的指针接口。点击操作光标位置，确认操作当前高亮项；具体效果取决于电视和应用。"))
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "接收器当前可用操作")).font(.headline)
            ForEach(context.televisionActions, id: \.self) { action in
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text(actionTitle(action))
                    Spacer(minLength: 8)
                    availabilityLabel(context.availability(of: action))
                }.font(.subheadline)
            }
            Text(String(localized: "电视暂不支持右键、鼠标双击、拖动、键盘或语音输入。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            Text(String(localized: "轻震动表示手机已接收操作；电视是否执行请以画面为准。操作被拒绝时会显示提示并给出不同反馈。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            HStack(spacing: 12) {
                VStack(spacing: 8) {
                    Image(systemName: "chevron.backward").font(.title2)
                    Text(String(localized: "返回")).font(.caption)
                }.frame(maxWidth: .infinity)
                VStack(spacing: 8) {
                    Image(systemName: "house").font(.title2)
                    Text(String(localized: "电视主页")).font(.caption)
                }.frame(maxWidth: .infinity)
                Color.clear.frame(maxWidth: .infinity, maxHeight: 44)
            }
            .foregroundStyle(Palette.mint).padding(.vertical, 12)
            .background(Palette.background, in: RoundedRectangle(cornerRadius: 16))
            // Keep the Back / Home illustration in a consistent order across languages.
            .environment(\.layoutDirection, .leftToRight)
            Text(String(localized: "在手势设置中可绑定确认、返回和主页。返回与主页也可使用专用按钮。"))
                .font(.footnote).foregroundStyle(Palette.muted)
        }
        .padding(18).background(Palette.panel, in: RoundedRectangle(cornerRadius: 20))
    }

    private var mappingCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(String(localized: "当前手势配置"), systemImage: "hand.draw").font(.headline)
            ForEach(GestureID.allCases, id: \.self) { gesture in
                let action = currentConfiguration.action(for: gesture)
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text(gesture.title).foregroundStyle(Palette.muted)
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(actionTitle(action))
                        if context.availability(of: action) == .unsupported {
                            Text(String(localized: "当前设备暂不支持此操作")).font(.caption).foregroundStyle(Palette.muted)
                        } else if context.availability(of: action) == .unknown {
                            Text(String(localized: "等待设备报告")).font(.caption).foregroundStyle(Palette.muted)
                        }
                    }.multilineTextAlignment(.trailing)
                }.font(.subheadline)
                .accessibilityIdentifier("guideBinding_\(gesture.rawValue)")
            }
            Text(String(localized: "右侧滚动条固定用于滚动；手势按上方当前映射执行。滚动需要目标内容支持。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            Text(String(localized: "未设置双击动作时，每次轻点仍执行单击动作。"))
                .font(.footnote).foregroundStyle(Palette.muted)
            Text(String(localized: "在手势设置中查看和修改操作。"))
                .font(.footnote).foregroundStyle(Palette.muted)
        }
        .padding(18).background(Palette.panel, in: RoundedRectangle(cornerRadius: 20))
    }

    private func actionTitle(_ action: GestureAction) -> String {
        if target == .television && action == .confirm { return String(localized: "确认当前高亮项") }
        return action.title(for: target)
    }

    private func availabilityLabel(_ availability: DeviceGuideContext.Availability) -> some View {
        let title: String
        switch availability {
        case .supported: title = String(localized: "支持")
        case .unsupported: title = String(localized: "暂不支持")
        case .unknown: title = String(localized: "等待设备报告")
        case .disabled: title = String(localized: "不执行操作")
        }
        return Text(title).foregroundStyle(availability == .supported ? Palette.mint : Palette.muted)
            .multilineTextAlignment(.trailing)
    }

    private func guideCard(_ symbol: String, gesture: GuideGesture, title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            GuideGestureDiagram(gesture: gesture).frame(height: 88).accessibilityHidden(true)
            Label(title, systemImage: symbol).font(.system(size: 16, weight: .semibold))
            Text(detail).font(.system(size: 14)).foregroundStyle(Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 20))
    }
}

private enum GuideGesture { case move, tap, hold, scroll, text, backspace, tvFocus }

private struct GuideGestureDiagram: View {
    let gesture: GuideGesture
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 15).fill(Palette.background)
            Canvas { context, size in
                for row in 0..<5 {
                    for column in 0..<16 {
                        let rect = CGRect(x: CGFloat(column) * size.width / 16 + 8,
                                          y: CGFloat(row) * 17 + 8, width: 2, height: 2)
                        context.fill(Path(ellipseIn: rect), with: .color(Palette.mint.opacity(0.18)))
                    }
                }
            }
            HStack(spacing: 16) {
                if gesture == .tvFocus {
                    RoundedRectangle(cornerRadius: 8).fill(Palette.mint.opacity(0.12))
                        .frame(width: 70, height: 46)
                        .overlay(Image(systemName: "cursorarrow"))
                    RoundedRectangle(cornerRadius: 8).stroke(Palette.mint, lineWidth: 3)
                        .frame(width: 70, height: 46)
                        .overlay(Image(systemName: "checkmark"))
                } else if gesture == .tap {
                    // Show the invisible physical halves only in this guide. Keep
                    // their positions stable in right-to-left interface languages.
                    tapHalf(String(localized: "左键 ×2"))
                    Rectangle().fill(Palette.mint.opacity(0.25)).frame(width: 1, height: 58)
                    tapHalf(String(localized: "右键 ×2"))
                } else if gesture == .text {
                    Image(systemName: "keyboard"); Image(systemName: "mic.fill"); Image(systemName: "arrow.up.circle.fill")
                } else if gesture == .backspace {
                    Image(systemName: "delete.left"); Image(systemName: "arrow.right"); Image(systemName: "desktopcomputer")
                } else {
                    if gesture == .hold {
                        Circle().stroke(Palette.mint.opacity(0.30), lineWidth: 2).frame(width: 46, height: 46)
                            .overlay(Circle().fill(Palette.mint).frame(width: 17, height: 17))
                    } else {
                        HStack(spacing: 7) {
                            Capsule().fill(Palette.mint).frame(width: 12, height: 29)
                            if gesture == .scroll { Capsule().fill(Palette.mint).frame(width: 12, height: 29) }
                        }
                    }
                    if gesture == .hold { Text(verbatim: "0.3 s").font(.system(size: 17, weight: .medium, design: .rounded)) }
                    Image(systemName: gesture == .scroll ? "arrow.up.arrow.down" : "arrow.right")
                }
            }
            .font(.system(size: 24, weight: .medium)).foregroundStyle(Palette.mint)
            .environment(\.layoutDirection, .leftToRight)
        }
    }

    private func tapHalf(_ label: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: "hand.tap")
            Text(label).font(.system(size: 12, weight: .semibold))
        }
        .frame(maxWidth: .infinity)
    }
}

struct RemoteIconButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        let pressed = isEnabled && configuration.isPressed
        configuration.label
            .scaleEffect(pressed && !reduceMotion ? 0.92 : 1)
            .opacity(pressed ? 0.65 : isEnabled ? 1 : 0.40)
            .animation(reduceMotion ? nil : .easeOut(duration: pressed ? 0.10 : 0.16), value: pressed)
    }
}
