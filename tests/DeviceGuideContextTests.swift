import Foundation

@main
struct DeviceGuideContextTests {
    static func main() {
        var passed = 0
        func check(_ label: String, _ value: @autoclosure () -> Bool) {
            precondition(value(), label)
            passed += 1
        }

        let fullTV: Set<GestureAction> = [.movePointer, .leftClick, .scroll, .confirm, .back, .home]
        var customized = GestureConfiguration(preset: .pressMenu, target: .television)
        customized.setAction(.back, for: .singleTap)
        customized.setAction(.leftClick, for: .twoFingerTap)
        customized.setAction(.scroll, for: .oneFingerMove)
        customized.setAction(.movePointer, for: .twoFingerMove)
        check("guide preserves actual custom actions instead of teaching default tap and swipe behavior", lg.configuration(customized) == customized)
        let staleDesktop = GestureConfiguration(preset: .trackpad, target: .computer)
        check("a TV guide never teaches desktop double-clicks or dragging", lg.configuration(staleDesktop).action(for: .leftDoubleTap) == .none && lg.configuration(staleDesktop).action(for: .longPress) == .none)
        check("TV example distinguishes pointer movement from confirmation", lg.televisionActions.contains(.movePointer) && lg.televisionActions.contains(.confirm))

        let lg = DeviceGuideContext(kind: .webOS, deviceName: "LG TV", connected: true, availableActions: fullTV)
        check("LG help uses the TV profile and omits text instructions", lg.target == .television && !lg.showsTextInputHelp)
        for platform in ["windows", "macos", "linux"] {
            let context = DeviceGuideContext(kind: .computer(platform: platform), deviceName: "Computer", connected: true, availableActions: Set(GestureAction.allCases))
            check("\(platform) retains desktop guide and text help", context.target == .computer && context.showsTextInputHelp)
            check("\(platform) keeps desktop drag and double-click mappings", context.configuration(staleDesktop) == staleDesktop)
        }
        let fallback = DeviceGuideContext(kind: .computer(platform: nil), deviceName: "Select device", connected: false, availableActions: nil)
        check("unknown desktop platform remains a generic computer guide", fallback.kind == .computer && fallback.availability(of: .leftClick) == .unknown)
        print("PASS: \(passed) device-aware guide checks; no devices or input used.")
    }
}
