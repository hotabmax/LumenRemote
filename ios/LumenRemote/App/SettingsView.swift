import SwiftUI

/// Настройки: relay-сервер, параметры передачи, сведения о приложении.
struct SettingsView: View {

    @EnvironmentObject private var settings: AppSettings

    @State private var draftURL = ""
    @State private var validationMessage: String?
    @State private var savedMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                relaySection
                streamingSection
                aboutSection
            }
            .navigationTitle(L10n.settingsTitle)
            .onAppear {
                draftURL = settings.relayURL
            }
        }
    }

    private var relaySection: some View {
        Section(L10n.relaySection) {
            TextField("wss://relay.example.com/ws", text: $draftURL)
                .keyboardType(.URL)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            Button(L10n.save) { save() }

            if let validationMessage {
                Text(validationMessage).font(.footnote).foregroundStyle(.red)
            }
            if let savedMessage {
                Text(savedMessage).font(.footnote).foregroundStyle(.green)
            }
            Text(L10n.relayHint).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var streamingSection: some View {
        Section(L10n.frameRateSection) {
            Stepper(
                "\(L10n.frameRate): \(settings.frameRate)",
                value: $settings.frameRate,
                in: 4...24
            )
            VStack(alignment: .leading, spacing: 6) {
                Text("\(L10n.quality): \(Int((settings.quality * 100).rounded())) %")
                    .font(.subheadline)
                Slider(value: $settings.quality, in: 0.3...0.9, step: 0.05)
            }
            .padding(.vertical, 4)
        }
    }

    private var aboutSection: some View {
        Section(L10n.aboutSection) {
            LabeledContent(L10n.version, value: version)
            Text(L10n.privacyNote).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func save() {
        validationMessage = nil
        savedMessage = nil
        let trimmed = draftURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            validationMessage = L10n.relayRequired
            return
        }
        guard WireProtocol.isRelayURLAllowed(trimmed) else {
            validationMessage = L10n.errorWssOnly
            return
        }
        settings.relayURL = trimmed
        savedMessage = L10n.saved
    }

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "\(short) (\(build))"
    }
}
