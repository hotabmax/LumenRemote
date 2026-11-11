import CoreImage
import ReplayKit
import UIKit

/// Расширение ReplayKit: кодирует экран iPhone в JPEG и отправляет на relay.
///
/// Код, PIN, адрес сервера и параметры передачи читаются из App Group
/// (`SessionSettings`). Команды партнёра (URL, буфер обмена) складываются
/// в App Group и применяются основным приложением при активации.
final class BroadcastSampleHandler: RPBroadcastSampleHandler {

    private let settings = SessionSettings.shared
    private let ciContext = CIContext()

    private var client: RelayClient?
    private var lastSentAt = Date.distantPast
    private var targetFps = 10
    private var jpegQuality: CGFloat = 0.6
    private var isStarting = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        targetFps = settings.frameRate
        jpegQuality = CGFloat(settings.quality)

        guard WireProtocol.isRelayURLAllowed(settings.relayURL) else {
            finishBroadcastWithError(
                NSError(
                    domain: "LumenRemote",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: L10n.relayRequired]
                )
            )
            return
        }
        guard let url = URL(string: settings.relayURL) else {
            finishBroadcastWithError(
                NSError(
                    domain: "LumenRemote",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: L10n.errorGeneric]
                )
            )
            return
        }

        let client = RelayClient(
            url: url,
            role: .host,
            code: settings.hostCode,
            pin: settings.hostPin
        )
        client.onEvent = { [weak self] event in
            self?.handle(event)
        }
        self.client = client
        client.connect()
    }

    override func processSampleBuffer(
        _ sampleBuffer: CMSampleBuffer,
        with sampleBufferType: RPSampleBufferType
    ) {
        guard sampleBufferType == .video, client != nil else { return }

        let now = Date()
        let interval = 1.0 / Double(max(targetFps, 1))
        guard now.timeIntervalSince(lastSentAt) >= interval else { return }
        lastSentAt = now

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else { return }

        let image = UIImage(cgImage: cgImage)
        guard let jpeg = image.jpegData(compressionQuality: jpegQuality) else { return }
        client?.sendFrame(jpeg)
    }

    override func broadcastPaused() {
        // Соединение сохраняется, кадры не отправляются.
    }

    override func broadcastResumed() {
        lastSentAt = .distantPast
    }

    override func broadcastFinished() {
        client?.close()
        client = nil
    }

    // MARK: События relay

    private func handle(_ event: RelayEvent) {
        switch event {
        case .ready:
            sendHello()
        case .json(let json):
            handleJSON(json)
        case .closed, .failed, .frame:
            break
        }
    }

    private func handleJSON(_ json: [String: Any]) {
        switch WireProtocol.type(of: json) {
        case WireProtocol.command:
            guard let command = WireProtocol.parseCommand(json) else { return }
            settings.enqueueCommand(action: command.action, value: command.value)
        case WireProtocol.quality:
            let quality = WireProtocol.parseQuality(
                json,
                defaultFps: targetFps,
                defaultQuality: Double(jpegQuality)
            )
            targetFps = quality.fps
            jpegQuality = CGFloat(quality.quality)
        case WireProtocol.peerLeft, WireProtocol.bye:
            finishBroadcastWithError(
                NSError(
                    domain: "LumenRemote",
                    code: 3,
                    userInfo: [NSLocalizedDescriptionKey: L10n.errorGeneric]
                )
            )
        default:
            break
        }
    }

    private func sendHello() {
        let hello = HelloMessage(
            name: settings.deviceName,
            platform: "iOS",
            model: UIDevice.current.model,
            width: 0,
            height: 0,
            // iOS не принимает инъекцию жестов: только команды и просмотр.
            control: false,
            pinRequired: true
        )
        client?.sendJSON(hello.json)
    }
}
