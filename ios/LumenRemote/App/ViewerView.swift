import SwiftUI
import UIKit
import Photos

/// Экран просмотра и управления удалённым устройством.
struct ViewerView: View {

    @EnvironmentObject private var settings: AppSettings
    @StateObject private var model = ViewerModel()

    @State private var code = ""
    @State private var pin = ""

    var body: some View {
        NavigationStack {
            Group {
                if model.isConnected || model.frame != nil {
                    session
                } else {
                    connectForm
                }
            }
            .navigationTitle(model.isConnected ? model.peerName : L10n.viewerTitle)
            .navigationBarTitleDisplayMode(.inline)
            .background(Color.black.ignoresSafeArea())
            .alert(L10n.keyboardPrompt, isPresented: $model.showKeyboard) {
                TextField(L10n.keyboardPrompt, text: $model.keyboardText)
                Button(L10n.send) { model.sendText(model.keyboardText) }
                Button(L10n.cancel, role: .cancel) {}
            }
        }
    }

    // MARK: Форма подключения

    private var connectForm: some View {
        Form {
            Section(L10n.viewerTitle) {
                TextField(L10n.codeHint, text: $code)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                SecureField(L10n.pinHint, text: $pin)
                    .keyboardType(.numberPad)
            }

            Section {
                Button(L10n.connect) {
                    model.connect(url: settings.relayURL, code: code, pin: pin)
                }
                if let error = model.errorText {
                    Text(error).foregroundStyle(.red).font(.footnote)
                } else {
                    Text(model.statusText).font(.footnote).foregroundStyle(.secondary)
                }
            }

            if !settings.relayConfigured {
                Section {
                    Text(L10n.relayRequired).font(.footnote).foregroundStyle(.orange)
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    // MARK: Сессия

    private var session: some View {
        VStack(spacing: 10) {
            header
            screen
            if model.isIosPeer {
                Text(L10n.iosNote)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
            }
            toolbar
        }
        .padding(12)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.peerName).font(.headline)
                Text(model.peerInfo).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text("\(model.latencyMs) \(L10n.t("мс", "ms")) · \(model.fps)")
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.white.opacity(0.08), in: Capsule())
        }
    }

    private var screen: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black
                if let frame = model.frame {
                    Image(uiImage: frame)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onEnded { value in
                                    model.handleGesture(
                                        start: value.startLocation,
                                        end: value.location,
                                        imageSize: frame.size,
                                        container: proxy.size
                                    )
                                }
                        )
                } else {
                    VStack(spacing: 12) {
                        ProgressView().tint(.cyan)
                        Text(model.statusText).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 18))
        }
    }

    private var toolbar: some View {
        HStack(spacing: 8) {
            if !model.isIosPeer {
                tool("keyboard") { model.showKeyboard = true }
                tool("arrow.uturn.backward") { model.sendKey(WireProtocol.keyBack) }
                tool("house") { model.sendKey(WireProtocol.keyHome) }
                tool("square.stack") { model.sendKey(WireProtocol.keyRecents) }
            }
            tool("link") { promptLink() }
            tool("doc.on.clipboard") { promptClipboard() }
            tool("camera") { model.saveSnapshot() }
            Spacer()
            tool("xmark", danger: true) { model.disconnect() }
        }
        .padding(10)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 20))
    }

    private func tool(
        _ symbol: String,
        danger: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .frame(width: 44, height: 44)
                .background(danger ? Color.red.opacity(0.18) : Color.white.opacity(0.1))
                .clipShape(Circle())
                .foregroundStyle(danger ? Color.red : Color.primary)
        }
        .buttonStyle(.plain)
    }

    private func promptLink() {
        let alert = UIAlertController(
            title: L10n.linkPrompt,
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "https://example.com"
            field.keyboardType = .URL
            field.autocapitalizationType = .none
        }
        alert.addAction(UIAlertAction(title: L10n.cancel, style: .cancel))
        alert.addAction(UIAlertAction(title: L10n.send, style: .default) { _ in
            let value = alert.textFields?.first?.text ?? ""
            model.sendLink(value)
        })
        present(alert)
    }

    private func promptClipboard() {
        let alert = UIAlertController(
            title: L10n.clipboardPrompt,
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = UIPasteboard.general.string ?? ""
        }
        alert.addAction(UIAlertAction(title: L10n.cancel, style: .cancel))
        alert.addAction(UIAlertAction(title: L10n.send, style: .default) { _ in
            let value = alert.textFields?.first?.text ?? ""
            model.sendClipboard(value)
        })
        present(alert)
    }

    private func present(_ controller: UIAlertController) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        root.present(controller, animated: true)
    }
}

private extension UIWindowScene {
    var keyWindow: UIWindow? {
        windows.first { $0.isKeyWindow }
    }
}

/// Состояние сеанса просмотра.
@MainActor
final class ViewerModel: ObservableObject {

    @Published var frame: UIImage?
    @Published var isConnected = false
    @Published var statusText = L10n.connecting
    @Published var errorText: String?
    @Published var peerName = ""
    @Published var peerInfo = ""
    @Published var isIosPeer = false
    @Published var latencyMs = 0
    @Published var fps = 0
    @Published var showKeyboard = false
    @Published var keyboardText = ""

    private var client: RelayClient?
    private var parameters: (url: String, code: String, pin: String)?
    private var pingTimer: Timer?
    private var statsTimer: Timer?
    private var framesInWindow = 0
    private var reconnectAttempt = 0
    private var shouldReconnect = false

    private let maxReconnectAttempts = 3

    // MARK: Управление сеансом

    func connect(url: String, code: String, pin: String) {
        let digits = code.filter { $0.isNumber }
        guard WireProtocol.isNineDigitCode(digits) else {
            errorText = L10n.errorCode
            return
        }
        guard WireProtocol.isFourDigitPin(pin) else {
            errorText = L10n.errorPin
            return
        }
        guard WireProtocol.isRelayURLAllowed(url) else {
            errorText = url.isEmpty ? L10n.relayRequired : L10n.errorWssOnly
            return
        }

        errorText = nil
        parameters = (url, digits, pin)
        reconnectAttempt = 0
        shouldReconnect = true
        startClient()
    }

    func disconnect() {
        shouldReconnect = false
        teardown()
        isConnected = false
        frame = nil
        statusText = L10n.viewerTitle
    }

    private func startClient() {
        guard let parameters, let url = URL(string: parameters.url) else {
            errorText = L10n.errorGeneric
            return
        }
        teardown()
        statusText = L10n.connecting

        let client = RelayClient(url: url, role: .viewer, code: parameters.code, pin: parameters.pin)
        client.onEvent = { [weak self] event in self?.handle(event) }
        self.client = client
        client.connect()

        pingTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            guard let self, let client = self.client else { return }
            client.sendJSON(WireProtocol.ping(timestamp: RelayClient.milliseconds()))
        }
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.updateStats()
        }
    }

    private func teardown() {
        pingTimer?.invalidate()
        pingTimer = nil
        statsTimer?.invalidate()
        statsTimer = nil
        let current = client
        client = nil
        current?.close()
    }

    // MARK: События

    private func handle(_ event: RelayEvent) {
        switch event {
        case .ready:
            guard let parameters else { return }
            client?.sendJSON(WireProtocol.auth(pin: parameters.pin))
        case .json(let json):
            handleJSON(json)
        case .frame(let data):
            guard let image = UIImage(data: data) else { return }
            frame = image
            if !isConnected {
                isConnected = true
                statusText = L10n.connected
            }
            framesInWindow += 1
        case .closed:
            handleDrop(reason: nil)
        case .failed(let reason):
            handleDrop(reason: reason)
        }
    }

    private func handleJSON(_ json: [String: Any]) {
        switch WireProtocol.type(of: json) {
        case WireProtocol.authOK:
            sendHello()
        case WireProtocol.authFail:
            errorText = L10n.errorWrongPin
            shouldReconnect = false
            teardown()
        case "hello":
            applyHello(WireProtocol.parseHello(json))
        case WireProtocol.pong:
            latencyMs = max(0, RelayClient.milliseconds() - WireProtocol.parseTimestamp(json))
        case WireProtocol.peerLeft:
            handleDrop(reason: "peer_left")
        case WireProtocol.bye:
            handleDrop(reason: "bye")
        case WireProtocol.ping:
            client?.sendJSON(WireProtocol.pong(timestamp: WireProtocol.parseTimestamp(json)))
        default:
            break
        }
    }

    private func applyHello(_ hello: HelloMessage) {
        peerName = hello.name
        peerInfo = "\(hello.platform) · \(L10n.t("защищённое соединение", "secure connection"))"
        isIosPeer = hello.platform.lowercased().contains("ios")
        isConnected = true
        statusText = L10n.connected
        reconnectAttempt = 0

        if let current = parameters {
            _ = current
            client?.sendQuality(
                fps: SessionSettings.shared.frameRate,
                quality: SessionSettings.shared.quality
            )
        }
    }

    private func sendHello() {
        let hello = HelloMessage(
            name: SessionSettings.shared.deviceName,
            platform: "iOS",
            model: UIDevice.current.model,
            width: 0,
            height: 0,
            control: false,
            pinRequired: false
        )
        client?.sendJSON(hello.json)
    }

    private func handleDrop(reason: String?) {
        isConnected = false
        frame = nil
        if let reason {
            errorText = mapError(reason)
        }
        teardown()
        guard shouldReconnect, reconnectAttempt < maxReconnectAttempts else {
            statusText = L10n.errorGeneric
            return
        }
        reconnectAttempt += 1
        statusText = L10n.connecting
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(reconnectAttempt) * 2) { [weak self] in
            guard let self, self.shouldReconnect else { return }
            self.startClient()
        }
    }

    private func mapError(_ reason: String) -> String {
        switch reason {
        case "wrong_pin", "auth_failed":
            return L10n.errorWrongPin
        case "not_found", "timeout":
            return L10n.errorNotFound
        case "busy":
            return L10n.errorBusy
        case "too_many_attempts":
            return L10n.errorTooMany
        case "tls_error":
            return L10n.errorTLS
        default:
            return L10n.errorGeneric
        }
    }

    private func updateStats() {
        fps = framesInWindow
        framesInWindow = 0
    }

    // MARK: Действия

    /// Переводит жест в нормализованные координаты 0..1 с учётом letterbox.
    func handleGesture(start: CGPoint, end: CGPoint, imageSize: CGSize, container: CGSize) {
        guard isConnected, !isIosPeer, imageSize.width > 0, imageSize.height > 0 else { return }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let width = imageSize.width * scale
        let height = imageSize.height * scale
        let originX = (container.width - width) / 2
        let originY = (container.height - height) / 2

        func normalize(_ point: CGPoint) -> CGPoint? {
            let x = (point.x - originX) / width
            let y = (point.y - originY) / height
            guard x >= 0, x <= 1, y >= 0, y <= 1 else { return nil }
            return CGPoint(x: x, y: y)
        }

        guard let from = normalize(start), let to = normalize(end) else { return }
        let distance = hypot(to.x - from.x, to.y - from.y)
        if distance < 0.02 {
            client?.sendTap(x: Double(to.x), y: Double(to.y))
        } else {
            client?.sendSwipe(
                x1: Double(from.x),
                y1: Double(from.y),
                x2: Double(to.x),
                y2: Double(to.y),
                ms: 220
            )
        }
    }

    func sendKey(_ key: String) {
        guard isConnected else { return }
        client?.sendKey(key)
    }

    func sendText(_ text: String) {
        guard isConnected, !text.isEmpty else { return }
        client?.sendText(text)
        keyboardText = ""
    }

    func sendLink(_ value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowercased = trimmed.lowercased()
        guard lowercased.hasPrefix("http://") || lowercased.hasPrefix("https://") else {
            errorText = L10n.invalidURL
            return
        }
        client?.sendCommand(action: CommandMessage.openURL, value: trimmed)
    }

    func sendClipboard(_ value: String) {
        guard !value.isEmpty else { return }
        client?.sendCommand(action: CommandMessage.clipboard, value: value)
    }

    func saveSnapshot() {
        guard let frame else {
            errorText = L10n.waitingFrames
            return
        }
        UIImageWriteToSavedPhotosAlbum(frame, nil, nil, nil)
        statusText = L10n.snapshotSaved
    }
}
