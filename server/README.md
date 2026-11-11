# Lumen Relay — эксплуатация

Relay-сервер сопрягает пары «принимаю / смотрю» по 9-значному коду и PIN и пересылает
сообщения между участниками. Содержимое сеанса не сохраняется и не декодируется.

## Быстрый старт (Docker, TLS автоматически)

```bash
cd server
cp .env.example .env          # укажите RELAY_DOMAIN и ALLOWED_ORIGINS
docker compose up -d --build
curl -fsS https://<домен>/health
```

Caddy получит сертификат Let's Encrypt и проксирует трафик на relay.
Итоговые адреса:

- `wss://<домен>/ws` — для приложения и веб-клиента;
- `https://<домен>/` — веб-клиент для iPhone/iPad;
- `https://<домен>/health`, `/ready` — проверки для мониторинга.

## Без Docker (systemd)

```bash
sudo useradd --system --home /opt/lumen --shell /usr/sbin/nologin lumen
sudo mkdir -p /opt/lumen/server /etc/lumen
sudo cp -r relay.js web package.json /opt/lumen/server/
cd /opt/lumen/server && sudo -u lumen npm install --omit=dev

sudo cp server/lumen-relay.service /etc/systemd/system/
sudo cp server/.env.example /etc/lumen/relay.env     # заполните значения
sudo systemctl daemon-reload
sudo systemctl enable --now lumen-relay
journalctl -u lumen-relay -f
```

TLS-терминацию в этом варианте выполняет nginx/Caddy перед сервисом
(сервис слушает `127.0.0.1:8080`, `HOST=127.0.0.1`).

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `PORT`, `HOST` | `8080`, `0.0.0.0` | адрес прослушивания |
| `ALLOWED_ORIGINS` | пусто (любые) | белый список Origin для WebSocket, через запятую |
| `MAX_ROOMS` | `5000` | максимум активных комнат |
| `MAX_PAYLOAD_BYTES` | `8388608` | предел размера одного сообщения |
| `ROOM_TTL_MS` | `43200000` | время жизни комнаты (12 часов) |
| `HEARTBEAT_MS` | `30000` | частота ping/pong для мёртвых сокетов |
| `JOIN_WINDOW_MS`, `JOIN_MAX_ATTEMPTS` | `60000`, `10` | лимит попыток входа с одного IP (антиперебор PIN) |
| `LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `TRUST_PROXY` | `0` | доверять `X-Forwarded-For` (только за своим прокси) |

Для продакшена обязательно задайте `ALLOWED_ORIGINS` (например `https://relay.example.com`)
и `TRUST_PROXY=1` при работе за Caddy/nginx.

## Проверки и мониторинг

```bash
curl -fsS http://127.0.0.1:8080/health   # {"status":"ok","uptimeSeconds":…}
curl -fsS http://127.0.0.1:8080/ready    # {"status":"ready","rooms":…,"connections":…}
```

Логи — JSON-строки в stdout (удобно для Loki/CloudWatch/journald).
Ключевые события: `relay_started`, `host_joined`, `viewer_joined`, `viewer_wrong_pin`,
`join_rate_limited`, `room_expired`, `shutdown_started/completed`.

Рекомендуемые алерты:

- `ready` недоступен > 1 мин;
- рост `viewer_wrong_pin` / `join_rate_limited` (попытки подбора);
- `rooms_limit_reached` (пора масштабировать или чистить комнаты).

## Масштабирование

Сервер хранит комнаты в памяти процесса. Для нескольких инстансов нужен общий
брокер (Redis/NATS) или sticky-маршрутизация по коду комнаты на балансировщике.
Начните с вертикального масштабирования: один инстанс держит тысячи комнат,
трафик — JPEG-кадры 50–150 КБ при 8–12 к/с на сеанс.

## Безопасность

- Только TLS: клиенты в release используют `wss://` (cleartext заблокирован манифестом).
- PIN проверяется на сервере; при неверном PIN соединение закрывается (`wrong_pin`).
- Ограничение частоты входов с IP и белый список Origin включены по умолчанию в конфиге.
- Сервер не логирует PIN, коды комнат попадают в логи только при `LOG_LEVEL=debug`/`info`
  в событиях `*_joined` — при необходимости отключите их фильтром на сборщике логов.
- Контейнер работает от непривилегированного пользователя `node`; systemd-юнит
  использует `ProtectSystem`, `NoNewPrivileges`, `PrivateTmp`.

## Обновление

```bash
git pull
docker compose up -d --build     # пересоздаст контейнер с новым кодом
curl -fsS https://<домен>/ready
```

Активные сеансы при перезапуске прерываются: приложение переподключится
(relay-режим автоматически повторяет подключение), пользователю нужно повторить вход.
Планируйте обновления в окно низкой нагрузки.

## Диагностика

| Симптом | Проверить |
|---|---|
| Клиент не подключается | `ALLOWED_ORIGINS` содержит origin веб-клиента; TLS-сертификат валиден |
| `not_found` при входе | принимающее устройство ещё не создало комнату или другой код |
| `wrong_pin` | PIN на принимающей стороне изменился |
| `busy` | к комнате уже подключён зритель/хост |
| `too_many_attempts` | лимит попыток входа с IP (см. `JOIN_*`) |
| Высокая задержка | проверьте канал до сервера, снизьте fps/качество в приложении |
