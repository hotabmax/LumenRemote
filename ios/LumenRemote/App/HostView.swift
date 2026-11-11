import SwiftUI
import ReplayKit
import UIKit

/// Экран приёма: код, PIN и запуск трансляции экрана iPhone.
struct HostView: View {

    @EnvironmentObject private var settings: AppSettings
    @State private var copiedValue: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    header
                    credentialsCard
                    broadcastCard
                    limitations
                }
                .padding(20)
            }
            .background(background)
            .navigationTitle(L10n.hostTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var background: some View {
        LinearGradient(
            colors: [Color(red: 0.03, green: 0.04, blue: 0.09), Color.black],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text(L10n.hostTitle).font(.title2).bold()
            Text(L10n.hostSubtitle)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var credentialsCard: some View {
        VStack(spacing: 14) {
            Text(L10n.codeLabel.uppercased())
                .font(.caption2)
                .tracking(1.4)
                .foregroundStyle(.secondary)
            Text(WireProtocol.formattedCode(settings.hostCode))
                .font(.system(.largeTitle, design: .monospaced))
                .bold()
                .onTapGesture { copy(settings.hostCode) }

            HStack(spacing: 8) {
                Text("\(L10n.pinLabel) \(settings.hostPin)")
                    .font(.caption)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.orange.opacity(0.18), in: Capsule())
                Button(L10n.copy) { copy(settings.hostPin) }
                    .font(.caption)
            }

            Divider().opacity(0.3)

            HStack {
                Button(L10n.regenerate) { settings.rotateCredentials() }
                    .font(.footnote)
                Spacer()
                if let copiedValue {
                    Text(copiedValue)
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
    }

    private var broadcastCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 14) {
                BroadcastPickerButton()
                    .frame(width: 54, height: 54)
                VStack(alignment: .leading, spacing: 4) {
                    Text(L10n.startBroadcast).font(.headline)
                    Text(L10n.broadcastHint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }

            if !settings.relayConfigured {
                Text(L10n.relayRequired)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let command = settings.pendingCommandText {
                Text("\(L10n.pendingCommand): \(command)")
                    .font(.caption)
                    .foregroundStyle(.cyan)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(18)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    private var limitations: some View {
        Text(L10n.iosLimits)
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 8)
    }

    private func copy(_ value: String) {
        UIPasteboard.general.string = value
        copiedValue = L10n.copied
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            copiedValue = nil
        }
    }
}

/// Системная кнопка запуска трансляции ReplayKit.
struct BroadcastPickerButton: UIViewRepresentable {

    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let view = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 54, height: 54))
        view.preferredExtension = SharedConfig.broadcastBundleId
        view.showsMicrophoneButton = false
        return view
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
