import Foundation

/// Локализация: две строки на ключ, выбор по языку системы.
///
/// Для App Store можно перейти на `.strings`-файлы, сохранив сигнатуры вызовов.
enum L10n {

    private static var isRussian: Bool {
        Locale.current.language.languageCode?.identifier == "ru"
    }

    static func t(_ russian: String, _ english: String) -> String {
        isRussian ? russian : english
    }

    // Экраны и вкладки
    static var hostTab: String { t("Принимаю", "Host") }
    static var viewerTab: String { t("Смотрю", "Viewer") }
    static var settingsTab: String { t("Настройки", "Settings") }

    // Приём подключения
    static var hostTitle: String { t("Приём подключения", "Host session") }
    static var hostSubtitle: String {
        t(
            "Партнёр увидит экран iPhone и сможет открыть ссылку или отправить текст",
            "The partner sees your iPhone screen and can open links or send text"
        )
    }
    static var codeLabel: String { t("Код устройства", "Device code") }
    static var pinLabel: String { t("PIN-код", "PIN") }
    static var copy: String { t("Копировать", "Copy") }
    static var copied: String { t("Скопировано", "Copied") }
    static var regenerate: String { t("Новый код", "New code") }
    static var startBroadcast: String { t("Начать трансляцию", "Start broadcast") }
    static var broadcastHint: String {
        t(
            "Нажмите кнопку и выберите Lumen Remote в системном окне «Повтор экрана»",
            "Tap the button and pick Lumen Remote in the system screen broadcast sheet"
        )
    }
    static var iosLimits: String {
        t(
            "iOS не позволяет удалённо нажимать на экране. Доступны просмотр, ссылки и буфер обмена.",
            "iOS does not allow remote taps. Viewing, links and clipboard are available."
        )
    }
    static var relayRequired: String {
        t("Укажите адрес relay-сервера в настройках", "Set the relay server URL in Settings")
    }
    static var pendingCommand: String { t("Команда от партнёра", "Command from partner") }

    // Просмотр
    static var viewerTitle: String { t("Подключение", "Connect") }
    static var codeHint: String { t("Код устройства или IP", "Device code or IP") }
    static var pinHint: String { t("PIN-код", "PIN") }
    static var connect: String { t("Подключиться", "Connect") }
    static var disconnect: String { t("Завершить", "Disconnect") }
    static var connecting: String { t("Устанавливаем соединение…", "Connecting…") }
    static var connected: String { t("Подключено", "Connected") }
    static var retry: String { t("Повторить", "Retry") }
    static var control: String { t("Управление", "Control") }
    static var viewOnly: String { t("Просмотр", "View only") }
    static var iosChip: String { t("iOS · просмотр", "iOS · view") }
    static var keyboard: String { t("Клавиатура", "Keyboard") }
    static var back: String { t("Назад", "Back") }
    static var home: String { t("Домой", "Home") }
    static var recents: String { t("Недавние", "Recents") }
    static var link: String { t("Ссылка", "Link") }
    static var clipboard: String { t("Буфер", "Clipboard") }
    static var screenshot: String { t("Снимок", "Snapshot") }
    static var snapshotSaved: String { t("Снимок сохранён в Фото", "Snapshot saved to Photos") }
    static var waitingFrames: String { t("Ожидаем изображение…", "Waiting for frames…") }
    static var keyboardPrompt: String { t("Текст для партнёра", "Text for the partner") }
    static var send: String { t("Отправить", "Send") }
    static var cancel: String { t("Отмена", "Cancel") }
    static var linkPrompt: String { t("Адрес для браузера партнёра", "URL for the partner's browser") }
    static var clipboardPrompt: String { t("Текст в буфер партнёра", "Text for the partner's clipboard") }
    static var invalidURL: String {
        t("Адрес должен начинаться с http:// или https://", "URL must start with http:// or https://")
    }
    static var linkSent: String { t("Ссылка отправлена", "Link sent") }
    static var clipboardSent: String { t("Текст отправлен", "Text sent") }
    static var iosNote: String {
        t(
            "iOS-устройство передаёт экран через ReplayKit. Система iOS не разрешает удалённые касания.",
            "iOS shares its screen through ReplayKit. iOS does not permit remote touches."
        )
    }

    // Ошибки
    static var errorCode: String { t("Введите 9-значный код", "Enter the 9-digit code") }
    static var errorPin: String { t("Введите 4-значный PIN", "Enter the 4-digit PIN") }
    static var errorWrongPin: String { t("Неверный PIN-код", "Wrong PIN") }
    static var errorNotFound: String { t("Устройство не найдено", "Device not found") }
    static var errorBusy: String { t("Устройство занято", "Device is busy") }
    static var errorTooMany: String {
        t("Слишком много попыток. Подождите минуту", "Too many attempts, wait a minute")
    }
    static var errorTLS: String {
        t("Не удалось установить защищённое соединение", "Secure connection failed")
    }
    static var errorWssOnly: String {
        t(
            "В релизной сборке допускается только адрес wss://",
            "Only a wss:// URL is allowed in release builds"
        )
    }
    static var errorGeneric: String { t("Не удалось подключиться", "Connection failed") }

    // Настройки
    static var settingsTitle: String { t("Настройки", "Settings") }
    static var relaySection: String { t("Relay-сервер", "Relay server") }
    static var relayHint: String {
        t(
            "В релизной сборке допускается только wss://",
            "Only wss:// is allowed in release builds"
        )
    }
    static var save: String { t("Сохранить", "Save") }
    static var saved: String { t("Сохранено", "Saved") }
    static var frameRateSection: String { t("Передача", "Streaming") }
    static var frameRate: String { t("Частота кадров", "Frame rate") }
    static var quality: String { t("Качество JPEG", "JPEG quality") }
    static var aboutSection: String { t("О приложении", "About") }
    static var version: String { t("Версия", "Version") }
    static var privacyNote: String {
        t(
            "Экран и команды передаются только во время сеанса. Данные не хранятся на сервере.",
            "Screen and commands are transferred only during a session. Nothing is stored on the server."
        )
    }
}
