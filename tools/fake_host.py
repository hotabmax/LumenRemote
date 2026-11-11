"""Тестовый «принимающий» узел Lumen Remote для локальной сети.

Поднимает TCP-сервер на порту 8731, отвечает как телефон-хост:
auth_ok + hello, затем непрерывно отправляет JPEG-кадры и печатает
команды управления, пришедшие от приложения-клиента.

Пример:
    python tools/fake_host.py --image screenshots/test.jpg --pin 4821
"""
import argparse
import json
import socket
import threading
import time

import lumen_wire


def load_stream(path: str) -> list[tuple[int, bytes]]:
    """Читает записи [kind][len][data] из файла, снятого fake_viewer."""
    with open(path, "rb") as handle:
        data = handle.read()
    packets: list[tuple[int, bytes]] = []
    offset = 0
    while offset + 5 <= len(data):
        kind = data[offset]
        size = int.from_bytes(data[offset + 1:offset + 5], "big")
        offset += 5
        packets.append((kind, data[offset:offset + size]))
        offset += size
    return packets


def video_loop(sock: socket.socket, packets: list[tuple[int, bytes]],
               interval: float) -> None:
    """Проигрывает записанный H.264-поток по кругу."""
    sent = 0
    try:
        while True:
            for kind, payload in packets:
                lumen_wire.send_video(sock, bytes([kind]) + payload)
                sent += 1
                if sent % 40 == 0:
                    print(f"[video] отправлено {sent}", flush=True)
                time.sleep(interval)
    except OSError:
        print("[video] соединение закрыто", flush=True)


def frame_loop(sock: socket.socket, jpeg: bytes, interval: float) -> None:
    """Отправляет кадры до разрыва соединения."""
    sent = 0
    try:
        while True:
            lumen_wire.send_frame(sock, jpeg)
            sent += 1
            if sent % 40 == 0:
                print(f"[frames] отправлено {sent}", flush=True)
            time.sleep(interval)
    except OSError:
        print("[frames] соединение закрыто", flush=True)


def serve(args: argparse.Namespace) -> None:
    """Ждёт подключения клиента и обслуживает один сеанс."""
    jpeg = b""
    packets: list[tuple[int, bytes]] = []
    if args.h264:
        packets = load_stream(args.h264)
        print(f"H.264 {len(packets)} пакетов из {args.h264}")
    else:
        with open(args.image, "rb") as handle:
            jpeg = handle.read()
        print(f"JPEG {len(jpeg)} байт из {args.image}")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(1)
    print(f"Жду подключения на 0.0.0.0:{args.port} …", flush=True)

    conn, addr = server.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"Клиент подключился: {addr}", flush=True)

    streaming = False
    try:
        while True:
            message = lumen_wire.recv_message(conn)
            if message is None:
                print("Клиент отключился", flush=True)
                break
            kind, payload = message
            if kind != lumen_wire.TYPE_JSON:
                continue
            try:
                obj = json.loads(payload.decode("utf-8"))
            except json.JSONDecodeError:
                continue
            print("RX", obj, flush=True)

            msg_type = obj.get("t")
            if msg_type == "auth":
                lumen_wire.send_json(conn, {
                    "t": "auth_ok",
                    "name": args.name,
                    "platform": "Android",
                    "model": "Pixel 7 Pro",
                    "w": 1080,
                    "h": 2400,
                    "control": True,
                    "pin": True,
                })
                lumen_wire.send_json(conn, lumen_wire.make_hello(args.name))
                if not streaming:
                    streaming = True
                    if packets:
                        target = video_loop
                        thread_args = (conn, packets, args.interval)
                    else:
                        target = frame_loop
                        thread_args = (conn, jpeg, args.interval)
                    threading.Thread(
                        target=target,
                        args=thread_args,
                        daemon=True,
                    ).start()
            elif msg_type == "ping":
                lumen_wire.send_json(
                    conn, {"t": "pong", "ts": obj.get("ts", 0)}
                )
    finally:
        conn.close()
        server.close()


def parse_args() -> argparse.Namespace:
    """Разбор аргументов командной строки."""
    parser = argparse.ArgumentParser(description="Тестовый хост Lumen Remote")
    parser.add_argument("--image", default="screenshots/test.jpg",
                        help="JPEG-кадр для трансляции")
    parser.add_argument("--port", type=int, default=lumen_wire.DEFAULT_PORT,
                        help="TCP-порт сеанса")
    parser.add_argument("--name", default="Fake Pixel",
                        help="Имя устройства в списке партнёра")
    parser.add_argument("--interval", type=float, default=0.12,
                        help="Пауза между кадрами, секунды")
    parser.add_argument("--h264", default="",
                        help="Файл записи fake_viewer (.stream) для H.264")
    return parser.parse_args()


if __name__ == "__main__":
    serve(parse_args())
