"""Тестовый «просматривающий» узел Lumen Remote.

Подключается к принимающему устройству по локальной сети, проходит проверку
PIN, отправляет hello, принимает JPEG-кадры, сохраняет первый кадр в файл
и отправляет тестовое касание и клавишу «Домой».

Пример (после `adb forward tcp:8731 tcp:8731`):
    python tools/fake_viewer.py --pin 7307 --out frame.jpg
"""
import argparse
import json
import time

import lumen_wire

TAP_FRAME = 5


def receive(sock, out_path: str, seconds: float) -> tuple[int, int]:
    """Принимает сообщения сеанса: (кадры JPEG, видеопакеты H.264)."""
    frames = 0
    video = 0
    saved = False
    deadline = time.time() + seconds
    while time.time() < deadline:
        message = lumen_wire.recv_message(sock)
        if message is None:
            print("Соединение закрыто", flush=True)
            break
        kind, payload = message
        if kind == lumen_wire.TYPE_JSON:
            print("RX", json.loads(payload.decode("utf-8")), flush=True)
            continue
        if kind == lumen_wire.TYPE_VIDEO:
            video += 1
            if video == 1:
                packet_kind = "config" if payload[:1] == b"\x01" else "frame"
                with open(out_path + ".h264", "wb") as handle:
                    handle.write(payload[1:])
                print(
                    f"Первый видеопакет {packet_kind}: {len(payload)} байт",
                    flush=True,
                )
            with open(out_path + ".stream", "ab") as handle:
                handle.write(bytes([payload[0]]))
                handle.write(len(payload).to_bytes(4, "big"))
                handle.write(payload[1:])
            if video % 20 == 0:
                print(f"[video] пакетов {video}", flush=True)
            continue
        frames += 1
        if not saved:
            with open(out_path, "wb") as handle:
                handle.write(payload)
            saved = True
            print(
                f"Первый кадр: {len(payload)} байт -> {out_path}",
                flush=True,
            )
        if frames == TAP_FRAME:
            lumen_wire.send_json(sock, {"t": "tap", "x": 0.5, "y": 0.5})
            lumen_wire.send_json(sock, {"t": "key", "k": "home"})
            print("Отправили tap и key=home", flush=True)
        if frames % 20 == 0:
            print(f"[frames] принято {frames}", flush=True)
    return frames, video


def app_handshake(sock, hello: dict, seconds: float = 3.0) -> bool:
    """Ведёт себя как Android-зритель: ждёт auth_ok/hello, потом шлёт hello."""
    deadline = time.time() + seconds
    sock.settimeout(1.0)
    try:
        while time.time() < deadline:
            try:
                message = lumen_wire.recv_message(sock)
            except OSError:
                continue
            if message is None:
                return False
            kind, payload = message
            if kind != lumen_wire.TYPE_JSON:
                continue
            obj = json.loads(payload.decode("utf-8"))
            print("RX", obj, flush=True)
            if obj.get("t") in ("auth_ok", "hello"):
                lumen_wire.send_json(sock, hello)
                return True
        return False
    finally:
        sock.settimeout(None)


def run(args: argparse.Namespace) -> None:
    """Открывает сеанс с принимающим узлом."""
    sock = lumen_wire.connect(args.host, args.port, timeout=args.timeout)
    print(f"Подключились к {args.host}:{args.port}", flush=True)
    hello = lumen_wire.make_hello(
        "Fake Viewer", control=False, codecs=args.codecs
    )
    lumen_wire.send_json(sock, lumen_wire.make_auth(args.pin))
    if args.mimic_app:
        print("Жду auth_ok как приложение…", flush=True)
        app_handshake(sock, hello)
    else:
        lumen_wire.send_json(sock, hello)
    try:
        frames, video = receive(sock, args.out, args.seconds)
        print(f"Итого кадров: {frames}, видеопакетов: {video}", flush=True)
    finally:
        sock.close()


def parse_args() -> argparse.Namespace:
    """Разбор аргументов командной строки."""
    parser = argparse.ArgumentParser(
        description="Тестовый клиент Lumen Remote",
    )
    parser.add_argument(
        "--host", default="127.0.0.1", help="IP принимающего узла",
    )
    parser.add_argument(
        "--port", type=int, default=lumen_wire.DEFAULT_PORT,
        help="TCP-порт сеанса",
    )
    parser.add_argument(
        "--pin", default="7307", help="PIN-код принимающего узла",
    )
    parser.add_argument(
        "--codecs", default="h264",
        help="Кодеки зрителя (h264 — запросить видео)",
    )
    parser.add_argument(
        "--mimic-app", action="store_true",
        help="Ждать auth_ok перед hello, как Android-зритель",
    )
    parser.add_argument(
        "--out", default="out.jpg", help="Файл для первого кадра",
    )
    parser.add_argument(
        "--seconds", type=float, default=20.0,
        help="Длительность сеанса, секунды",
    )
    parser.add_argument(
        "--timeout", type=float, default=12.0,
        help="Таймаут ожидания сообщения, секунды",
    )
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
