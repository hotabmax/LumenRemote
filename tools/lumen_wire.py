"""Общие примитивы протокола Lumen Remote для тестовых узлов.

Кадр локального транспорта: [1 байт тип][4 байта длина][payload].
Тип 1 — управляющий JSON, тип 2 — JPEG-кадр экрана.
"""
import json
import socket
import struct

TYPE_JSON = 1
TYPE_FRAME = 2
TYPE_VIDEO = 3

DEFAULT_PORT = 8731
DISCOVERY_PORT = 8890

HEADER_SIZE = 5


def encode(kind: int, payload: bytes) -> bytes:
    """Собирает кадр протокола с заголовком."""
    return struct.pack("!BI", kind, len(payload)) + payload


def send_json(sock: socket.socket, payload: dict) -> None:
    """Отправляет управляющее JSON-сообщение."""
    sock.sendall(encode(TYPE_JSON, json.dumps(payload).encode("utf-8")))


def send_frame(sock: socket.socket, jpeg: bytes) -> None:
    """Отправляет кадр экрана (JPEG)."""
    sock.sendall(encode(TYPE_FRAME, jpeg))


def send_video(sock: socket.socket, packet: bytes) -> None:
    """Отправляет видеопакет H.264 ([kind][data])."""
    sock.sendall(encode(TYPE_VIDEO, packet))


def recv_exact(sock: socket.socket, size: int) -> bytes | None:
    """Читает ровно size байт; None, если соединение закрыто."""
    buffer = b""
    while len(buffer) < size:
        chunk = sock.recv(size - len(buffer))
        if not chunk:
            return None
        buffer += chunk
    return buffer


def recv_message(sock: socket.socket) -> tuple[int, bytes] | None:
    """Читает один кадр протокола: (тип, payload) или None."""
    header = recv_exact(sock, HEADER_SIZE)
    if header is None:
        return None
    kind, length = struct.unpack("!BI", header)
    payload = recv_exact(sock, length)
    if payload is None:
        return None
    return kind, payload


def connect(
    host: str,
    port: int = DEFAULT_PORT,
    timeout: float = 8.0,
) -> socket.socket:
    """Открывает TCP-соединение с узлом локальной сети."""
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return sock


def make_auth(pin: str) -> dict:
    """Сообщение проверки PIN-кода."""
    return {"t": "auth", "pin": pin}


def make_hello(
    name: str,
    model: str = "Test Harness",
    control: bool = True,
    width: int = 1080,
    height: int = 2400,
    platform: str = "Android",
    codecs: str = "",
) -> dict:
    """Приветствие узла: имя, платформа, размеры экрана, кодеки."""
    payload = {
        "t": "hello",
        "name": name,
        "platform": platform,
        "model": model,
        "w": width,
        "h": height,
        "control": control,
        "pin": True,
    }
    if codecs:
        payload["codecs"] = codecs
    return payload
