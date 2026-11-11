"""Дымовой тест relay: зритель подключается к комнате и получает ready.

Запуск (предварительно: relay и тестовый хост уже работают):
    python tools/relay_smoke.py --url ws://127.0.0.1:8080/ws \
        --code 284913706 --pin 4821
"""
import argparse
import asyncio
import json

import websockets


async def run(url: str, code: str, pin: str, timeout: float) -> int:
    """Возвращает 0, если сервер прислал ready, иначе 1."""
    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(json.dumps({"t": "viewer", "code": code, "pin": pin}))
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
        except asyncio.TimeoutError:
            print("FAIL: нет ответа от relay")
            return 1
        message = json.loads(raw)
        print("RX", message)
        if message.get("t") == "ready":
            print("OK: relay сопряг комнату")
            return 0
        print("FAIL:", message.get("reason", "неизвестная ошибка"))
        return 1


def main() -> None:
    """Разбор аргументов и запуск теста."""
    parser = argparse.ArgumentParser(description="Дымовой тест relay")
    parser.add_argument("--url", default="ws://127.0.0.1:8080/ws")
    parser.add_argument("--code", default="284913706")
    parser.add_argument("--pin", default="4821")
    parser.add_argument("--timeout", type=float, default=5.0)
    args = parser.parse_args()
    result = asyncio.run(run(args.url, args.code, args.pin, args.timeout))
    raise SystemExit(result)


if __name__ == "__main__":
    main()
