import XCTest
@testable import LumenRemote

final class ProtocolTests: XCTestCase {

    func testHelloCarriesPlatformAndCapabilities() {
        let hello = HelloMessage(
            name: "iPhone",
            platform: "iOS",
            model: "iPhone",
            width: 1170,
            height: 2532,
            control: false,
            pinRequired: true
        )
        XCTAssertEqual(hello.json["t"] as? String, "hello")
        XCTAssertEqual(hello.json["platform"] as? String, "iOS")
        XCTAssertEqual(hello.json["control"] as? Bool, false)
        XCTAssertEqual(hello.json["w"] as? Int, 1170)
    }

    func testParseHelloFillsDefaults() {
        let parsed = WireProtocol.parseHello(["t": "hello"])
        XCTAssertEqual(parsed.name, "Устройство")
        XCTAssertEqual(parsed.platform, "Android")
        XCTAssertFalse(parsed.control)
        XCTAssertTrue(parsed.pinRequired)
    }

    func testParseCommand() {
        let command = WireProtocol.parseCommand([
            "t": "cmd",
            "action": CommandMessage.openURL,
            "value": "https://example.com",
        ])
        XCTAssertEqual(command?.action, "open_url")
        XCTAssertEqual(command?.value, "https://example.com")
        XCTAssertNil(WireProtocol.parseCommand(["t": "cmd"]))
    }

    func testTapAndSwipePayload() {
        let tap = WireProtocol.tap(x: 0.5, y: 0.25)
        XCTAssertEqual(tap["t"] as? String, "tap")
        XCTAssertEqual(tap["x"] as? Double, 0.5)

        let swipe = WireProtocol.swipe(x1: 0.1, y1: 0.2, x2: 0.8, y2: 0.9, ms: 240)
        XCTAssertEqual(swipe["ms"] as? Int, 240)
        XCTAssertEqual(swipe["x2"] as? Double, 0.8)
    }

    func testQualityClamping() {
        let result = WireProtocol.parseQuality(["fps": 100, "q": 5], defaultFps: 10, defaultQuality: 0.6)
        XCTAssertEqual(result.fps, 24)
        XCTAssertEqual(result.quality, 0.3, accuracy: 0.0001)
    }

    func testRelayURLPolicy() {
        XCTAssertTrue(WireProtocol.isRelayURLAllowed("wss://relay.example.com/ws"))
        XCTAssertFalse(WireProtocol.isRelayURLAllowed("https://relay.example.com"))
        XCTAssertFalse(WireProtocol.isRelayURLAllowed(""))
        #if DEBUG
        XCTAssertTrue(WireProtocol.isRelayURLAllowed("ws://127.0.0.1:8080/ws"))
        #else
        XCTAssertFalse(WireProtocol.isRelayURLAllowed("ws://127.0.0.1:8080/ws"))
        #endif
    }

    func testCodeFormattingAndValidation() {
        XCTAssertEqual(WireProtocol.formattedCode("284913706"), "284 913 706")
        XCTAssertTrue(WireProtocol.isNineDigitCode("284 913 706"))
        XCTAssertFalse(WireProtocol.isNineDigitCode("28491370"))
        XCTAssertTrue(WireProtocol.isFourDigitPin("4821"))
        XCTAssertFalse(WireProtocol.isFourDigitPin("482"))
        XCTAssertFalse(WireProtocol.isFourDigitPin("48a1"))
    }
}
