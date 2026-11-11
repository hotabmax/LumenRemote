import Foundation

/// Роль клиента в сеансе.
enum LumenRole: String {
    case host
    case viewer
}

/// События соединения.
enum RelayEvent {
    case ready
    case json([String: Any])
    case frame(Data)
    case closed(String?)
    case failed(String)
}

/// WebSocket-клиент протокола Lumen Remote.
///
/// Отправляет `{"t":"host"|"viewer","code","pin"}`, ждёт `{"t":"ready"}`,
/// далее обменивается JSON-сообщениями и бинарными JPEG-кадрами.
/// Класс потокобезопасен: отправка возможна с любого потока, события
/// доставляются на главный поток.
final class RelayClient {

    private let url: URL
    private let role: LumenRole
    private let code: String
    private let pin: String
    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var keepalive: Timer?
    private let lock = NSLock()
    private var closed = false

    /// Обработчик событий вызывается на главном потоке.
    var onEvent: ((RelayEvent) -> Void)?

    init(url: URL, role: LumenRole, code: String, pin: String) {
        self.url = url
        self.role = role
        self.code = code
        self.pin = pin
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.waitsForConnectivity = true
        self.session = URLSession(configuration: configuration)
    }

    var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !closed && task != nil
    }

    func connect() {
        let task = session.webSocketTask(with: url)
        lock.lock()
        self.task = task
        lock.unlock()

        task.resume()
        sendJSON(["t": role.rawValue, "code": code, "pin": pin])
        receiveLoop()
        startKeepalive()
    }

    func close(reason: String? = nil) {
        lock.lock()
        let hadTask = task != nil
        closed = true
        let current = task
        task = nil
        lock.unlock()

        stopKeepalive()
        guard hadTask, let current else { return }
        sendJSON(WireProtocol.bye())
        current.cancel(with: .normalClosure, reason: reason?.data(using: .utf8))
        emit(.closed(reason))
    }

    // MARK: - Отправка

    func sendJSON(_ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else { return }
        currentTask()?.send(.string(text)) { _ in }
    }

    func sendFrame(_ jpeg: Data) {
        currentTask()?.send(.data(jpeg)) { _ in }
    }

    func sendTap(x: Double, y: Double) {
        sendJSON(WireProtocol.tap(x: x, y: y))
    }

    func sendSwipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int) {
        sendJSON(WireProtocol.swipe(x1: x1, y1: y1, x2: x2, y2: y2, ms: ms))
    }

    func sendKey(_ key: String) {
        sendJSON(WireProtocol.key(key))
    }

    func sendText(_ value: String) {
        sendJSON(WireProtocol.text(value))
    }

    func sendCommand(action: String, value: String) {
        sendJSON(WireProtocol.command(action: action, value: value))
    }

    func sendQuality(fps: Int, quality: Double) {
        sendJSON(WireProtocol.quality(fps: fps, jpegQuality: quality))
    }

    // MARK: - Приём

    private func currentTask() -> URLSessionWebSocketTask? {
        lock.lock()
        defer { lock.unlock() }
        return task
    }

    private func receiveLoop() {
        currentTask()?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                self.emit(.failed(Self.describe(error)))
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handle(text: text)
                case .data(let data):
                    self.emit(.frame(data))
                @unknown default:
                    break
                }
                self.receiveLoop()
            }
        }
    }

    private func handle(text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        switch WireProtocol.type(of: json) {
        case "ready":
            emit(.ready)
        case "error":
            emit(.failed(json["reason"] as? String ?? "relay_error"))
        case WireProtocol.ping:
            sendJSON(WireProtocol.pong(timestamp: WireProtocol.parseTimestamp(json)))
        default:
            emit(.json(json))
        }
    }

    private func startKeepalive() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.stopKeepalive()
            let timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
                guard let self, self.isOpen else { return }
                self.sendJSON(WireProtocol.ping(timestamp: Self.milliseconds()))
            }
            self.keepalive = timer
        }
    }

    private func stopKeepalive() {
        keepalive?.invalidate()
        keepalive = nil
    }

    private func emit(_ event: RelayEvent) {
        DispatchQueue.main.async { self.onEvent?(event) }
    }

    static func milliseconds() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }

    private static func describe(_ error: Error) -> String {
        let nsError = error as NSError
        switch nsError.code {
        case NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost:
            return "not_found"
        case NSURLErrorTimedOut:
            return "timeout"
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted:
            return "tls_error"
        default:
            return error.localizedDescription
        }
    }
}
