import Foundation

// Calls the actual ControllerModel through its compile-time-only recording
// transport. There is no session, pairing store, window, timer, or OS input.
@MainActor
private final class DragFixture {
    struct Frame {
        let type: String
        let fields: [String: Any]
        var ack: ((Bool) -> Void)?
        var tag: String {
            type == "button" ? ((fields["down"] as? Bool) == true ? "down" : "up") : type
        }
    }
    var frames: [Frame] = []
    lazy var model = ControllerModel(testSend: { [unowned self] type, fields, ack in
        self.frames.append(Frame(type: type, fields: fields, ack: ack))
    })
    var tags: [String] { frames.map(\.tag) }
    func ack(_ index: Int, _ accepted: Bool = true) {
        guard let callback = frames[index].ack else { preconditionFailure("ACK replay in test") }
        frames[index].ack = nil
        callback(accepted)
    }
    func begin() { model.setDragging(true); ack(0) }
}

@main
struct ControllerDragReleaseTests {
    @MainActor static func main() {
        var passed = 0
        func check(_ name: String, _ value: @autoclosure () -> Bool) {
            guard value() else { fputs("FAIL: \(name)\n", stderr); exit(1) }
            passed += 1
        }

        let tail = DragFixture()
        tail.begin()
        tail.model.move(dx: 20, dy: -18)
        tail.model.setDragging(false)
        check("release flushes tail immediately ahead of mouse-up", tail.tags == ["down", "move"])
        check("frozen tail preserves both axes", tail.frames[1].fields["dx"] as? Double == 20 && tail.frames[1].fields["dy"] as? Double == -18)
        check("input stays paused while the remote button may still be held", !tail.model.canControl)
        check("pending release does not report session loss to the UI", tail.model.controlAvailable)
        tail.model.testFlushMovement()
        tail.model.pauseInput()
        tail.model.setDragging(false)
        check("timer and UI cancellation cannot drop or duplicate the tail", tail.tags == ["down", "move"])
        check("release waiting keeps the same UI availability state", tail.model.controlAvailable)
        tail.ack(1)
        check("tail ACK permits exactly one mouse-up", tail.tags == ["down", "move", "up"])
        check("button-up ACK is required before next gesture", !tail.model.canControl)
        check("waiting for mouse-up ACK does not stop dictation via availability", tail.model.controlAvailable)
        tail.ack(2)
        tail.model.testFlushMovement()
        check("no old movement escapes after mouse-up", tail.tags == ["down", "move", "up"] && tail.model.canControl)
        check("completed release never toggles the session availability gate", tail.model.controlAvailable)

        let busy = DragFixture()
        busy.begin()
        busy.model.move(dx: 94, dy: -76)
        busy.model.testFlushMovement()
        busy.model.move(dx: 20, dy: -18)
        busy.model.setDragging(false)
        check("release waits for an already in-flight move", busy.tags == ["down", "move"])
        busy.model.move(dx: 200, dy: 200)
        busy.model.click("right")
        busy.model.setDragging(true)
        busy.model.testFlushMovement()
        check("a new gesture cannot overtake the pending release", busy.tags == ["down", "move"])
        Thread.sleep(forTimeInterval: 0.14)
        busy.ack(1)
        check("drag tail survives the normal movement freshness cutoff", busy.tags == ["down", "move", "move"] && busy.frames[2].fields["dx"] as? Double == 20)
        busy.ack(2)
        check("in-flight move then tail then up remain ordered", busy.tags == ["down", "move", "move", "up"])
        busy.ack(3)
        busy.model.setDragging(true)
        check("next drag begins only after the previous up ACK", busy.tags == ["down", "move", "move", "up", "down"])
        busy.ack(4)
        busy.model.setDragging(false)
        busy.ack(5)

        let noTail = DragFixture()
        noTail.begin()
        noTail.model.setDragging(false)
        check("stationary hold releases without an invented move", noTail.tags == ["down", "up"])
        noTail.ack(1)

        let busyNoTail = DragFixture()
        busyNoTail.begin()
        busyNoTail.model.move(dx: 10, dy: 0)
        busyNoTail.model.testFlushMovement()
        busyNoTail.model.setDragging(false)
        check("even empty tail waits for the last in-flight move", busyNoTail.tags == ["down", "move"])
        busyNoTail.ack(1)
        check("empty tail follows ACK with up", busyNoTail.tags == ["down", "move", "up"])
        busyNoTail.ack(2)

        let failedTail = DragFixture()
        failedTail.begin()
        failedTail.model.move(dx: 20, dy: -18)
        failedTail.model.setDragging(false)
        failedTail.ack(1, false)
        check("tail rejection disconnects for receiver release cleanup", !failedTail.model.connected && !failedTail.model.canControl)
        check("real disconnect while input is already gated still requests UI cleanup", !failedTail.model.controlAvailable)
        failedTail.model.testFlushMovement()
        check("rejected tail is never replayed", failedTail.tags == ["down", "move"])

        let failedFlight = DragFixture()
        failedFlight.begin()
        failedFlight.model.move(dx: 94, dy: -76)
        failedFlight.model.testFlushMovement()
        failedFlight.model.move(dx: 20, dy: -18)
        failedFlight.model.setDragging(false)
        failedFlight.ack(1, false)
        check("in-flight rejection disconnects instead of continuing a partial drag", !failedFlight.model.connected && failedFlight.tags == ["down", "move"])

        let failedUp = DragFixture()
        failedUp.begin()
        failedUp.model.setDragging(false)
        failedUp.ack(1, false)
        check("mouse-up rejection keeps the disconnect release protection", !failedUp.model.connected)

        let staleAck = DragFixture()
        staleAck.begin()
        staleAck.model.move(dx: 10, dy: 5)
        staleAck.model.setDragging(false)
        staleAck.model.disconnect(message: "test interruption")
        staleAck.ack(1)
        check("late ACK after disconnect cannot advance the old drag", staleAck.tags == ["down", "move"] && !staleAck.model.connected)
        check("late ACK cannot restore the disconnected availability gate", !staleAck.model.controlAvailable)

        let television = DragFixture()
        television.begin()
        television.model.pauseInput()
        check("switching away releases held desktop input first", television.tags == ["down", "up"])
        television.model.setDesktopControlEnabled(false)
        check("TV selection disables computer transport and input", !television.model.desktopControlEnabled && !television.model.connected && !television.model.canControl)
        television.ack(1)
        television.model.becameActive()
        television.model.connect()
        television.model.move(dx: 100, dy: 20)
        television.model.testFlushMovement()
        television.model.click("left")
        television.model.key("enter")
        check("scene activation and late ACK cannot resume desktop while TV selected", television.tags == ["down", "up"] && !television.model.canControl && !television.model.connecting)
        television.model.setDesktopControlEnabled(true)
        check("explicit computer selection restores mode but does not invent a connection", television.model.desktopControlEnabled && !television.model.connected)

        let television = DragFixture()
        television.begin()
        television.model.pauseInput()
        television.model.setDesktopControlEnabled(false)
        television.ack(1)
        television.model.becameActive()
        television.model.connect()
        television.model.move(dx: 50, dy: 10)
        television.model.testFlushMovement()
        television.model.click("left")
        check("TV selection releases drag and blocks desktop reconnect/input", television.tags == ["down", "up"] && !television.model.desktopControlEnabled && !television.model.canControl && !television.model.connecting)
        television.model.becameActive()
        check("TV mode stays disconnected from the computer", !television.model.connected && !television.model.connecting)
        television.model.setDesktopControlEnabled(true)
        check("returning from TV restores only desktop mode", television.model.desktopControlEnabled && !television.model.connected)

        print("Controller drag release: \(passed) recording-transport checks passed without windows or OS input")
    }
}
