"""Тестовый «принимающий» узел Lumen Remote для режима relay (WebSocket).

Подключается к relay-серверу как host, проходит сопряжение по коду и PIN,
отвечает на auth/hello, отправляет JPEG-кадры и печатает команды управления.

Пример:
    python tools/fake_relay_host.py --image screenshots/test.jpg
"""
import argparse
import asyncio
import json

import websockets

import lumen_wire

ROLE_HOST = "host"


async def stream(ws, jpeg: bytes, interval: float) -> None:
    """Непрерывно отправляет кадры зрителю."""
    sent = 0
    try:
        while True:
            await ws.send(jpeg)
            sent += 1
            if sent % 40 == 0:
                print(f"[frames] отправлено {sent}", flush=True)
            await asyncio.sleep(interval)
    except websockets.ConnectionClosed:
        print("[frames] зритель отключился", flush=True)


async def serve(args: argparse.Namespace) -> None:
    """Сопрягается с relay и обслуживает один сеанс."""
    with open(args.image, "rb") as handle:
        jpeg = handle.read()
    print(f"JPEG {len(jpeg)} байт, relay {args.url}, код {args.code}")

    async with websockets.connect(args.url, max_size=None) as ws:
        await ws.send(json.dumps({
            "t": ROLE_HOST,
            "code": args.code,
            "pin": args.pin,
        }))
        print("Отправили host-join, ждём ready…", flush=True)

        streaming = False
        while True:
            try:
                message = await ws.recv()
            except websockets.ConnectionClosed:
                print("Соединение с relay закрыто", flush=True)
                break
            if isinstance(message, bytes):
                continue
            try:
                obj = json.loads(message)
            except json.JSONDecodeError:
                continue

            print("RX", obj, flush=True)
            msg_type = obj.get("t")

            if msg_type == "ready":
                print("relay готов, ждём зрителя…", flush=True)
            elif msg_type == "auth":
                await ws.send(json.dumps({
                    "t": "auth_ok",
                    "name": args.name,
                    "platform": "Android",
                    "model": "Pixel 7 Pro",
                    "w": 1080,
                    "h": 2400,
                    "control": True,
                    "pin": True,
                }))
                await ws.send(json.dumps(
                    lumen_wire.make_hello(args.name, platform=args.platform)
                ))
                if not streaming:
                    streaming = True
                    asyncio.create_task(stream(ws, jpeg, args.interval))
                    print("Начали отправку кадров", flush=True)
            elif msg_type == "ping":
                await ws.send(json.dumps({
                    "t": "pong",
                    "ts": obj.get("ts", 0),
                }))
            elif msg_type == "peer_left":
                print("Зритель отключился", flush=True)


def parse_args() -> argparse.Namespace:
    """Разбор аргументов командной строки."""
    parser = argparse.ArgumentParser(
        description="Тестовый relay-хост Lumen Remote",
    )
    parser.add_argument(
        "--url", default="ws://127.0.0.1:8080/ws", help="Адрес relay-сервера",
    )
    parser.add_argument(
        "--code", default="284913706", help="9-значный код комнаты",
    )
    parser.add_argument(
        "--pin", default="4821", help="PIN-код комнаты",
    )
    parser.add_argument(
        "--image", default="screenshots/test.jpg", help="JPEG-кадр",
    )
    parser.add_argument(
        "--name", default="Fake Pixel", help="Имя устройства",
    )
    parser.add_argument(
        "--platform", default="Android",
        help="Платформа в hello (Android/iOS)",
    )
    parser.add_argument(
        "--interval", type=float, default=0.12, help="Пауза между кадрами",
    )
    return parser.parse_args()


if __name__ == "__main__":
    asyncio.run(serve(parse_args()))
