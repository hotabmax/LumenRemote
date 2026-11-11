/**
 * Lumen Remote — relay-сервер сопряжения (production).
 *
 * Возможности:
 *  - переменные окружения для всех лимитов (см. .env.example);
 *  - белый список Origin для WebSocket;
 *  - ограничение частоты попыток входа с одного IP (защита от перебора PIN);
 *  - лимит комнат и размера сообщений;
 *  - структурированные JSON-логи, /health и /ready;
 *  - корректное завершение по SIGTERM/SIGINT (Docker и systemd);
 *  - раздача веб-клиента из web/.
 *
 * Протокол:
 *   host   → {"t":"host","code":"284913706","pin":"4821"}   → {"t":"ready"}
 *   viewer → {"t":"viewer","code":"284913706","pin":"4821"} → {"t":"ready"}
 *   далее сервер прозрачно пересылает текст и бинарь между участниками комнаты.
 */
const { WebSocketServer } = require('ws');
const fs = require('fs');
const http = require('http');
const path = require('path');

// region Конфигурация

function envInt(name, fallback, min) {
  const raw = Number(process.env[name]);
  if (!Number.isFinite(raw) || raw < min) return fallback;
  return Math.floor(raw);
}

const config = {
  port: envInt('PORT', 8080, 1),
  host: process.env.HOST || '0.0.0.0',
  maxPayloadBytes: envInt('MAX_PAYLOAD_BYTES', 8 * 1024 * 1024, 4096),
  maxRooms: envInt('MAX_ROOMS', 5000, 1),
  roomTtlMs: envInt('ROOM_TTL_MS', 12 * 60 * 60 * 1000, 60000),
  heartbeatMs: envInt('HEARTBEAT_MS', 30000, 5000),
  joinWindowMs: envInt('JOIN_WINDOW_MS', 60000, 1000),
  joinMaxAttempts: envInt('JOIN_MAX_ATTEMPTS', 10, 1),
  allowedOrigins: (process.env.ALLOWED_ORIGINS || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean),
  logLevel: (process.env.LOG_LEVEL || 'info').toLowerCase(),
  trustProxy: process.env.TRUST_PROXY === '1',
};

const LOG_LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };
const WEB_ROOT = path.join(__dirname, 'web');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
};

/** Структурированный лог в stdout (собирается Docker/journald). */
function log(level, message, extra) {
  const threshold = LOG_LEVELS[config.logLevel] || LOG_LEVELS.info;
  if (LOG_LEVELS[level] < threshold) return;
  const entry = { ts: new Date().toISOString(), level, message, ...(extra || {}) };
  console.log(JSON.stringify(entry));
}

// endregion

// region Комнаты и подключения

/** code -> { host, viewer, pin, createdAt } */
const rooms = new Map();
/** ip -> { count, resetAt } */
const joinAttempts = new Map();
const startedAt = Date.now();

let wss = null;

function isOriginAllowed(origin) {
  if (config.allowedOrigins.length === 0) return true;
  if (!origin) return false;
  return config.allowedOrigins.includes(origin);
}

function clientIp(request) {
  if (config.trustProxy) {
    const forwarded = request.headers['x-forwarded-for'];
    if (typeof forwarded === 'string' && forwarded.length > 0) {
      return forwarded.split(',')[0].trim();
    }
  }
  return request.socket.remoteAddress || 'unknown';
}

/** Возвращает true, если с этого IP превышен лимит попыток входа. */
function exceededJoinAttempts(ip) {
  const now = Date.now();
  const entry = joinAttempts.get(ip);
  if (!entry || now > entry.resetAt) {
    joinAttempts.set(ip, { count: 1, resetAt: now + config.joinWindowMs });
    return false;
  }
  entry.count += 1;
  return entry.count > config.joinMaxAttempts;
}

/** Безопасная отправка: сокет мог закрыться между проверкой и записью. */
function send(ws, payload) {
  if (!ws || ws.readyState !== ws.OPEN) return false;
  try {
    ws.send(typeof payload === 'string' ? payload : JSON.stringify(payload));
    return true;
  } catch (error) {
    log('debug', 'send_failed', { error: error.message });
    return false;
  }
}

function closeWith(ws, reason, code) {
  send(ws, { t: 'error', reason });
  setTimeout(() => {
    try {
      ws.close(code || 1008, reason);
    } catch (error) {
      log('debug', 'close_failed', { error: error.message });
    }
  }, 80);
}

function cleanupRoom(code) {
  const room = rooms.get(code);
  if (!room) return;
  if (!room.host && !room.viewer) rooms.delete(code);
}

/** Обрабатывает первое сообщение клиента: join host/viewer. */
function handleJoin(ws, message, ip) {
  const code = String(message.code || '').replace(/\D/g, '');
  const pin = String(message.pin || '');

  if (exceededJoinAttempts(ip)) {
    log('warn', 'join_rate_limited', { ip });
    closeWith(ws, 'too_many_attempts', 1013);
    return;
  }
  if (code.length !== 9) {
    closeWith(ws, 'bad_code');
    return;
  }

  if (message.t === 'host') {
    const existing = rooms.get(code);
    if (existing && existing.host) {
      closeWith(ws, 'busy', 1013);
      return;
    }
    const room = existing || { host: null, viewer: null, createdAt: Date.now() };
    room.host = ws;
    room.pin = pin;
    rooms.set(code, room);
    ws.role = 'host';
    ws.code = code;
    send(ws, { t: 'ready' });
    if (room.viewer) send(room.viewer, { t: 'peer_joined' });
    log('info', 'host_joined', { code, rooms: rooms.size });
    return;
  }

  if (message.t === 'viewer') {
    const room = rooms.get(code);
    if (!room || !room.host) {
      closeWith(ws, 'not_found');
      return;
    }
    if (room.pin && room.pin !== pin) {
      log('warn', 'viewer_wrong_pin', { code, ip });
      closeWith(ws, 'wrong_pin');
      return;
    }
    if (room.viewer) {
      closeWith(ws, 'busy', 1013);
      return;
    }
    room.viewer = ws;
    ws.role = 'viewer';
    ws.code = code;
    send(ws, { t: 'ready' });
    send(room.host, { t: 'peer_joined' });
    log('info', 'viewer_joined', { code });
    return;
  }

  closeWith(ws, 'bad_role');
}

// endregion

// region HTTP: статика и проверки живости

/** Раздаёт веб-клиент; выход за WEB_ROOT запрещён. */
function serveStatic(pathname, res, method) {
  const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const filePath = path.resolve(WEB_ROOT, relative);
  if (!filePath.startsWith(WEB_ROOT)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }
  fs.readFile(filePath, (error, content) => {
    if (error) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
    });
    res.end(method === 'HEAD' ? undefined : content);
  });
}

const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname;

  if (pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
    }));
    return;
  }
  if (pathname === '/ready') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ready',
      rooms: rooms.size,
      connections: wss ? wss.clients.size : 0,
    }));
    return;
  }
  serveStatic(pathname, res, req.method);
});

server.on('error', (error) => {
  log('error', 'http_server_error', { error: error.message });
});

// endregion

// region WebSocket-сервер

wss = new WebSocketServer({
  server,
  path: '/ws',
  maxPayload: config.maxPayloadBytes,
  verifyClient(info, done) {
    if (!isOriginAllowed(info.origin)) {
      log('warn', 'origin_rejected', { origin: info.origin });
      done(false, 403, 'Forbidden origin');
      return;
    }
    if (rooms.size >= config.maxRooms) {
      log('warn', 'rooms_limit_reached', { rooms: rooms.size });
      done(false, 503, 'Too many rooms');
      return;
    }
    done(true);
  },
});

wss.on('connection', (ws, request) => {
  const ip = clientIp(request);
  ws.isAlive = true;
  ws.role = null;
  ws.code = null;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (data, isBinary) => {
    try {
      if (!ws.role) {
        if (isBinary) {
          closeWith(ws, 'bad_request');
          return;
        }
        let message;
        try {
          message = JSON.parse(data.toString());
        } catch (error) {
          closeWith(ws, 'bad_request');
          return;
        }
        handleJoin(ws, message, ip);
        return;
      }

      const room = rooms.get(ws.code);
      if (!room) return;
      const peer = ws.role === 'host' ? room.viewer : room.host;
      if (!peer) {
        send(ws, { t: 'peer_left' });
        return;
      }
      if (peer.readyState === peer.OPEN) peer.send(data, { binary: isBinary });
    } catch (error) {
      log('error', 'message_failed', { error: error.message, role: ws.role });
    }
  });

  ws.on('close', () => {
    const room = ws.code ? rooms.get(ws.code) : null;
    if (room) {
      if (ws.role === 'host') {
        room.host = null;
        send(room.viewer, { t: 'peer_left' });
        rooms.delete(ws.code);
      } else if (ws.role === 'viewer') {
        room.viewer = null;
        send(room.host, { t: 'peer_left' });
      }
      cleanupRoom(ws.code);
    }
    log('info', 'disconnected', { role: ws.role, code: ws.code, rooms: rooms.size });
  });

  ws.on('error', (error) => {
    log('debug', 'socket_error', { error: error.message });
  });
});

wss.on('error', (error) => {
  log('error', 'ws_server_error', { error: error.message });
});

// endregion

// region Фоновые задачи

const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      ws.terminate();
      return;
    }
    ws.isAlive = false;
    try {
      ws.ping();
    } catch (error) {
      log('debug', 'ping_failed', { error: error.message });
    }
  });
}, config.heartbeatMs);

const housekeeping = setInterval(() => {
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (!room.host && !room.viewer) {
      rooms.delete(code);
      continue;
    }
    if (now - (room.createdAt || 0) > config.roomTtlMs) {
      log('info', 'room_expired', { code });
      send(room.host, { t: 'peer_left' });
      send(room.viewer, { t: 'peer_left' });
      rooms.delete(code);
    }
  }
  for (const [ip, entry] of joinAttempts) {
    if (now > entry.resetAt) joinAttempts.delete(ip);
  }
}, 30000);

heartbeat.unref();
housekeeping.unref();

// endregion

// region Завершение работы

let shuttingDown = false;

function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  log('info', 'shutdown_started', { signal, rooms: rooms.size });
  clearInterval(heartbeat);
  clearInterval(housekeeping);

  wss.clients.forEach((ws) => {
    try {
      ws.close(1001, 'server shutting down');
    } catch (error) {
      log('debug', 'shutdown_close_failed', { error: error.message });
    }
  });

  server.close(() => {
    log('info', 'shutdown_completed');
    process.exit(0);
  });

  setTimeout(() => {
    log('warn', 'shutdown_forced');
    process.exit(1);
  }, 10000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('unhandledRejection', (reason) => {
  log('error', 'unhandled_rejection', { reason: String(reason) });
});
process.on('uncaughtException', (error) => {
  log('error', 'uncaught_exception', { error: error.message });
  shutdown('uncaughtException');
});

// endregion

server.listen(config.port, config.host, () => {
  log('info', 'relay_started', {
    host: config.host,
    port: config.port,
    maxRooms: config.maxRooms,
    origins: config.allowedOrigins.length ? config.allowedOrigins : 'any',
  });
  console.log(`Lumen relay слушает ws://${config.host}:${config.port}/ws`);
});
