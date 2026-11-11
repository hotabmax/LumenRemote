"""Диагностика веб-клиента Lumen Remote через Chrome DevTools Protocol.

Запуск (после `adb forward tcp:9222 localabstract:chrome_devtools_remote`):
    python tools/inspect_web.py --reload     # сбросить кэш и перезагрузить
    python tools/inspect_web.py              # только прочитать состояние
"""
import argparse
import asyncio
import json
import urllib.request

import websockets

REQUESTS = {"id": 0}


def page_ws_url() -> str:
    """Возвращает WebSocket-адрес первой вкладки Chrome."""
    endpoint = "http://127.0.0.1:9222/json"
    with urllib.request.urlopen(endpoint, timeout=5) as response:
        targets = json.load(response)
    for target in targets:
        if target.get("type") == "page":
            return target["webSocketDebuggerUrl"]
    raise SystemExit("нет открытых вкладок Chrome")


async def call(ws, method: str, params: dict | None = None) -> dict:
    """Отправляет команду CDP и ждёт ответ."""
    REQUESTS["id"] += 1
    request_id = REQUESTS["id"]
    payload = {"id": request_id, "method": method, "params": params or {}}
    await ws.send(json.dumps(payload))
    while True:
        message = json.loads(await ws.recv())
        if message.get("id") == request_id:
            return message


async def evaluate(ws, expression: str) -> str:
    """Вычисляет выражение в контексте страницы."""
    response = await call(ws, "Runtime.evaluate", {
        "expression": expression,
        "returnByValue": True,
        "awaitPromise": True,
    })
    result = response.get("result", {}).get("result", {})
    if "value" in result:
        return str(result["value"])
    return json.dumps(response.get("result", {}), ensure_ascii=False)


async def reload_page() -> None:
    """Очищает кэш и перезагружает страницу (соединение CDP рвётся)."""
    try:
        async with websockets.connect(page_ws_url(), max_size=None) as ws:
            await call(ws, "Network.enable")
            await call(ws, "Network.clearBrowserCache")
            await call(ws, "Page.enable")
            await call(ws, "Page.reload", {"ignoreCache": True})
    except Exception:  # noqa: BLE001 — соединение закрывается при навигации
        pass
    print("кэш очищен, страница перезагружена")
    await asyncio.sleep(7)


async def tap(x_ratio: float, y_ratio: float) -> None:
    """Отправляет касание в центр области экрана через CDP."""
    async with websockets.connect(page_ws_url(), max_size=None) as ws:
        expression = (
            "JSON.stringify("
            "document.getElementById('screen').getBoundingClientRect())"
        )
        box = await evaluate(ws, expression)
        rect = json.loads(box)
        x = rect["left"] + rect["width"] * x_ratio
        y = rect["top"] + rect["height"] * y_ratio
        touch_point = {"x": int(x), "y": int(y)}
        await call(ws, "Input.dispatchTouchEvent", {
            "type": "touchStart",
            "touchPoints": [touch_point],
        })
        await asyncio.sleep(0.12)
        await call(ws, "Input.dispatchTouchEvent", {
            "type": "touchEnd",
            "touchPoints": [],
        })
        print(f"tap в ({int(x)},{int(y)}) внутри canvas")


async def main(reload: bool) -> None:
    """Печатает состояние формы и последние ошибки страницы."""
    if reload:
        await reload_page()
    url = page_ws_url()
    print("target:", url)
    async with websockets.connect(url, max_size=None) as ws:
        checks = {
            "hasApplyHash": "typeof applyHashParams",
            "url": "location.href",
            "code": "document.getElementById('code').value",
            "pin": "document.getElementById('pin').value",
            "relay": "document.getElementById('url').value",
            "status": "document.getElementById('statusChip').textContent",
            "error": "document.getElementById('error').textContent",
            "sessionHidden": "document.getElementById('sessionPanel').hidden",
            "canvas": (
                "document.getElementById('screen').width + 'x' + "
                "document.getElementById('screen').height"
            ),
        }
        for name, expression in checks.items():
            print(f"{name}: {await evaluate(ws, expression)}")


async def watch_errors(seconds: float = 7.0) -> None:
    """Перезагружает страницу и собирает ошибки JS и сообщения консоли."""
    url = page_ws_url()
    async with websockets.connect(url, max_size=None) as ws:
        await call(ws, "Runtime.enable")
        await call(ws, "Log.enable")
        await call(ws, "Network.enable")
        await call(ws, "Page.enable")
        await call(ws, "Page.reload", {"ignoreCache": True})
        deadline = asyncio.get_running_loop().time() + seconds
        while asyncio.get_running_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            message = json.loads(raw)
            method = message.get("method")
            if method == "Runtime.exceptionThrown":
                details = message["params"]["exceptionDetails"]
                exception = details.get("exception", {}).get("description")
                print("EXCEPTION:", details.get("text"), exception)
            elif method == "Log.entryAdded":
                entry = message["params"]["entry"]
                print(f"LOG[{entry.get('level')}]:", entry.get("text"))
            elif method == "Runtime.consoleAPICalled":
                params = message["params"]
                values = [
                    str(arg.get("value", arg.get("description")))
                    for arg in params.get("args", [])
                ]
                joined = " ".join(values)[:300]
                print(f"CONSOLE[{params.get('type')}]:", joined)
            elif method == "Network.loadingFailed":
                params = message["params"]
                print("NET FAIL:", params.get("type"), params.get("errorText"))
            elif method == "Network.responseReceived":
                response = message["params"]["response"]
                url = response.get("url", "")
                if url.endswith(".js") or ".js?" in url or url.endswith("/"):
                    status = response.get("status")
                    mime = response.get("mimeType")
                    print("NET:", status, mime, url)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Инспектор веб-клиента")
    parser.add_argument(
        "--reload", action="store_true", help="очистить кэш и перезагрузить",
    )
    parser.add_argument(
        "--eval", dest="expression", help="вычислить выражение на странице",
    )
    parser.add_argument(
        "--watch", action="store_true",
        help="перезагрузить и собрать ошибки JS",
    )
    parser.add_argument(
        "--tap", nargs=2, type=float, metavar=("X", "Y"),
        help="касание в долях canvas (0..1)",
    )
    args = parser.parse_args()
    if args.tap:
        asyncio.run(tap(args.tap[0], args.tap[1]))
    elif args.watch:
        asyncio.run(watch_errors())
    elif args.expression:
        async def run_once() -> None:
            target = page_ws_url()
            async with websockets.connect(target, max_size=None) as socket:
                print(await evaluate(socket, args.expression))
        asyncio.run(run_once())
    else:
        asyncio.run(main(args.reload))
