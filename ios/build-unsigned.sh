#!/usr/bin/env bash
# Сборка НЕподписанного iOS-приложения (.ipa) — без сертификатов и аккаунта Apple.
#
# Требуется macOS с Xcode и XcodeGen:  brew install xcodegen
# Запуск:      ./ios/build-unsigned.sh
# Свой путь:   ./ios/build-unsigned.sh --out /tmp/LumenRemote-unsigned.ipa
#
# Полученный .ipa не подписан: для установки на устройство его нужно
# переподписать своим Apple ID (Sideloadly, AltStore, Xcode) — либо
# использовать устройство с разрешённой установкой неподписанных сборок.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_PATH="${ROOT_DIR}/build/LumenRemote-unsigned.ipa"
DERIVED="${ROOT_DIR}/build/DerivedData"

usage() {
  echo "Использование: $0 [--out ПУТЬ.ipa] [--derived ПАПКА]" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT_PATH="$2"; shift 2 ;;
    --derived) DERIVED="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

command -v xcodebuild >/dev/null 2>&1 || {
  echo "Ошибка: нужна macOS с Xcode (xcodebuild не найден)." >&2
  exit 1
}
command -v xcodegen >/dev/null 2>&1 || {
  echo "Ошибка: установите XcodeGen: brew install xcodegen" >&2
  exit 1
}
command -v zip >/dev/null 2>&1 || {
  echo "Ошибка: нужен zip." >&2
  exit 1
}

cd "$ROOT_DIR"

echo "→ Генерация Xcode-проекта"
xcodegen generate --spec project.yml

echo "→ Сборка Release без подписи"
xcodebuild \
  -project LumenRemote.xcodeproj \
  -scheme LumenRemote \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$DERIVED" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  EXPANDED_CODE_SIGN_IDENTITY="" \
  DEVELOPMENT_TEAM="" \
  build

APP_PATH="${DERIVED}/Build/Products/Release-iphoneos/LumenRemote.app"
if [[ ! -d "$APP_PATH" ]]; then
  echo "Ошибка: не найден ${APP_PATH}" >&2
  exit 1
fi

echo "→ Упаковка .ipa"
STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT
mkdir -p "${STAGE_DIR}/Payload"
cp -R "$APP_PATH" "${STAGE_DIR}/Payload/"
mkdir -p "$(dirname "$OUT_PATH")"
rm -f "$OUT_PATH"
( cd "$STAGE_DIR" && zip -qry "$OUT_PATH" Payload )

echo "→ Проверка содержимого"
unzip -l "$OUT_PATH" | grep -q "Payload/LumenRemote.app/LumenRemote" || {
  echo "Ошибка: в архиве нет исполняемого файла приложения." >&2
  exit 1
}

echo "Готово: ${OUT_PATH}"
ls -lh "$OUT_PATH"
