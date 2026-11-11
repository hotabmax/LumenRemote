"""Мини-сервер статики для проверки веб-клиента (без кэширования).

Запуск: python tools/web_server.py 8000 server/web
"""
import functools
import http.server
import sys


class NoCacheHandler(http.server.SimpleHTTPRequestHandler):
    """Отдаёт файлы с запретом кэширования — удобно при отладке."""

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        sys.stderr.write("%s - %s\n" % (self.address_string(), format % args))


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    directory = sys.argv[2] if len(sys.argv) > 2 else "server/web"
    handler = functools.partial(NoCacheHandler, directory=directory)
    with http.server.ThreadingHTTPServer(("0.0.0.0", port), handler) as server:
        print(
            f"static server: http://0.0.0.0:{port}/ из {directory}",
            flush=True,
        )
        server.serve_forever()


if __name__ == "__main__":
    main()
