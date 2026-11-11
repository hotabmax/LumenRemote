import SwiftUI
import UIKit

@main
struct LumenRemoteApp: App {

    @StateObject private var settings = AppSettings()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        settings.applyPendingCommand()
                    }
                }
        }
    }
}

/// Корневой экран: три вкладки — приём, просмотр, настройки.
struct RootView: View {
    var body: some View {
        TabView {
            HostView()
                .tabItem { Label(L10n.hostTab, systemImage: "dot.radiowaves.left.and.right") }
            ViewerView()
                .tabItem { Label(L10n.viewerTab, systemImage: "rectangle.on.rectangle") }
            SettingsView()
                .tabItem { Label(L10n.settingsTab, systemImage: "slider.horizontal.3") }
        }
        .tint(Color(red: 0.55, green: 0.36, blue: 1.0))
    }
}

/// Состояние настроек, доступное представлениям.
@MainActor
final class AppSettings: ObservableObject {

    private let session = SessionSettings.shared

    @Published var relayURL: String {
        didSet { session.relayURL = relayURL }
    }

    @Published var frameRate: Int {
        didSet { session.frameRate = frameRate }
    }

    @Published var quality: Double {
        didSet { session.quality = quality }
    }

    @Published private(set) var hostCode: String
    @Published private(set) var hostPin: String
    @Published private(set) var pendingCommandText: String?

    init() {
        relayURL = session.relayURL
        frameRate = session.frameRate
        quality = session.quality
        hostCode = session.hostCode
        hostPin = session.hostPin
    }

    var relayConfigured: Bool {
        session.isRelayURLAllowed()
    }

    func rotateCredentials() {
        session.rotateCredentials()
        hostCode = session.hostCode
        hostPin = session.hostPin
    }

    /// Применяет команду, отложенную broadcast-расширением (URL или буфер обмена).
    func applyPendingCommand() {
        guard let command = session.dequeueCommand() else { return }
        pendingCommandText = "\(command.action): \(command.value)"

        switch command.action {
        case CommandMessage.openURL:
            if let url = URL(string: command.value),
               let scheme = url.scheme?.lowercased(),
               scheme == "http" || scheme == "https" {
                UIApplication.shared.open(url)
            }
        case CommandMessage.clipboard:
            UIPasteboard.general.string = command.value
        default:
            break
        }
    }
}
