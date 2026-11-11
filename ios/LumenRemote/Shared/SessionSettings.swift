import Foundation
import UIKit

/// Настройки сеанса, общие для приложения и broadcast-расширения (App Group).
final class SessionSettings {

    static let shared = SessionSettings()

    private let defaults: UserDefaults?

    /// Ключ, под которым расширение оставляет команду для основного приложения.
    static let pendingCommandKey = "pending_command"

    private enum Key {
        static let relayURL = "relay_url"
        static let hostCode = "host_code"
        static let hostPin = "host_pin"
        static let frameRate = "fps"
        static let quality = "quality"
        static let deviceName = "device_name"
    }

    init(suiteName: String = SharedConfig.appGroup) {
        defaults = UserDefaults(suiteName: suiteName)
    }

    var relayURL: String {
        get { defaults?.string(forKey: Key.relayURL) ?? "" }
        set {
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            defaults?.set(trimmed, forKey: Key.relayURL)
        }
    }

    var hostCode: String {
        get {
            if let stored = defaults?.string(forKey: Key.hostCode), stored.count == 9 {
                return stored
            }
            let generated = WireProtocol.newCode()
            defaults?.set(generated, forKey: Key.hostCode)
            return generated
        }
        set { defaults?.set(newValue, forKey: Key.hostCode) }
    }

    var hostPin: String {
        get {
            if let stored = defaults?.string(forKey: Key.hostPin), stored.count == 4 {
                return stored
            }
            let generated = WireProtocol.newPin()
            defaults?.set(generated, forKey: Key.hostPin)
            return generated
        }
        set { defaults?.set(newValue, forKey: Key.hostPin) }
    }

    /// Кадров в секунду при трансляции (4...24).
    var frameRate: Int {
        get {
            let stored = defaults?.integer(forKey: Key.frameRate) ?? 0
            return stored == 0 ? 10 : min(max(stored, 4), 24)
        }
        set { defaults?.set(min(max(newValue, 4), 24), forKey: Key.frameRate) }
    }

    /// Качество JPEG (0.3...0.9).
    var quality: Double {
        get {
            let stored = defaults?.double(forKey: Key.quality) ?? 0
            return stored == 0 ? 0.6 : min(max(stored, 0.3), 0.9)
        }
        set { defaults?.set(min(max(newValue, 0.3), 0.9), forKey: Key.quality) }
    }

    var deviceName: String {
        get {
            if let stored = defaults?.string(forKey: Key.deviceName), !stored.isEmpty {
                return stored
            }
            return UIDevice.current.name
        }
        set { defaults?.set(newValue, forKey: Key.deviceName) }
    }

    // MARK: Команды из расширения

    /// Расширение не может открывать URL и писать в буфер — складывает команду сюда.
    func enqueueCommand(action: String, value: String) {
        defaults?.set(["action": action, "value": value], forKey: Self.pendingCommandKey)
    }

    func dequeueCommand() -> CommandMessage? {
        guard let raw = defaults?.dictionary(forKey: Self.pendingCommandKey),
              let action = raw["action"] as? String else { return nil }
        defaults?.removeObject(forKey: Self.pendingCommandKey)
        return CommandMessage(action: action, value: raw["value"] as? String ?? "")
    }

    /// Перегенерация кода и PIN.
    func rotateCredentials() {
        hostCode = WireProtocol.newCode()
        hostPin = WireProtocol.newPin()
    }

    /// Проверка адреса сервера по политике сборки.
    func isRelayURLAllowed() -> Bool {
        WireProtocol.isRelayURLAllowed(relayURL)
    }
}
