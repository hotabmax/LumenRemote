"""Локальный relay-сервер Lumen Remote для автотестов.

Аналог `server/relay.js`: host и viewer подключаются с 9-значным кодом и PIN,
сервер сопрягает пару и прозрачно пересылает текстовые и бинарные сообщения.

Запуск: python tools/dev_relay.py 8080
"""
import asyncio
import json
import sys

import websockets

rooms: dict[str, dict] = {}


async def send_json(ws, payload: dict) -> None:
    """Отправляет JSON, игнорируя уже закрытые соединения."""
    if ws is None:
        return
    try:
        await ws.send(json.dumps(payload))
    except Exception:  # noqa: BLE001 — сокет мог закрыться в любой момент
        pass


def send_text(ws, payload: dict) -> None:
    """Отправляет JSON-сообщение, не дожидаясь результата."""
    if ws is None:
        return
    asyncio.create_task(send_json(ws, payload))


async def reject(ws, reason: str) -> None:
    """Отклоняет подключение с указанием причины."""
    await send_json(ws, {"t": "error", "reason": reason})
    try:
        await ws.close(1000, reason)
    except Exception:  # noqa: BLE001 — соединение уже потеряно
        pass


def room_pin_conflict(room: dict, pin: str) -> bool:
    """Проверяет несовпадение PIN-кода комнаты."""
    return bool(room.get("pin")) and room["pin"] != pin


async def handle_join(ws, obj: dict):
    """Обрабатывает первое сообщение клиента, возвращает (role, code)."""
    digits = "".join(ch for ch in str(obj.get("code", "")) if ch.isdigit())
    pin = str(obj.get("pin", ""))
    if len(digits) != 9:
        await reject(ws, "bad_code")
        return None, None

    role = obj.get("t")
    if role == "host":
        room = rooms.setdefault(
            digits, {"host": None, "viewer": None, "pin": pin}
        )
        if room.get("host") is not None:
            await reject(ws, "busy")
            return None, None
        room["host"] = ws
        room["pin"] = pin
        await send_json(ws, {"t": "ready"})
        print(f"[room {digits}] host connected", flush=True)
        if room.get("viewer") is not None:
            send_text(room["viewer"], {"t": "peer_joined"})
        return role, digits

    if role == "viewer":
        room = rooms.get(digits)
        if not room or room.get("host") is None:
            await reject(ws, "not_found")
            return None, None
        if room_pin_conflict(room, pin):
            await reject(ws, "wrong_pin")
            return None, None
        if room.get("viewer") is not None:
            await reject(ws, "busy")
            return None, None
        room["viewer"] = ws
        await send_json(ws, {"t": "ready"})
        send_text(room["host"], {"t": "peer_joined"})
        print(f"[room {digits}] viewer connected", flush=True)
        return role, digits

    await reject(ws, "bad_role")
    return None, None


async def forward(ws, role: str, code: str, message) -> None:
    """Пересылает сообщение второй стороне комнаты."""
    room = rooms.get(code)
    if not room:
        return
    peer = room.get("viewer") if role == "host" else room.get("host")
    if peer is None:
        await send_json(ws, {"t": "peer_left"})
        return
    try:
        await peer.send(message)
    except Exception:  # noqa: BLE001 — тестовый сервер
        pass


def cleanup(role: str | None, code: str | None) -> None:
    """Убирает отключившуюся сторону из комнаты."""
    room = rooms.get(code or "")
    if not room:
        return
    if role == "host":
        room["host"] = None
        send_text(room.get("viewer"), {"t": "peer_left"})
    elif role == "viewer":
        room["viewer"] = None
        send_text(room.get("host"), {"t": "peer_left"})
    if not room.get("host") and not room.get("viewer"):
        rooms.pop(code, None)
    print(f"disconnected: {role} {code}", flush=True)


async def handler(ws) -> None:
    """Обслуживает одно WebSocket-подключение."""
    role = None
    code = None
    try:
        async for message in ws:
            if role is None:
                if not isinstance(message, str):
                    await ws.close(1000, "bad_request")
                    return
                try:
                    obj = json.loads(message)
                except json.JSONDecodeError:
                    await ws.close(1000, "bad_request")
                    return
                role, code = await handle_join(ws, obj)
                if role is None:
                    return
                continue
            await forward(ws, role, code, message)
    finally:
        cleanup(role, code)


async def main() -> None:
    """Запускает сервер на указанном порту."""
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    async with websockets.serve(handler, "0.0.0.0", port, max_size=None):
        print(f"relay слушает ws://0.0.0.0:{port}/ws", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
