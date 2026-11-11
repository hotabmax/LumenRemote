'use strict';

/**
 * Lumen Remote — веб-клиент (iPhone/iPad/десктоп).
 *
 * Говорит по тому же протоколу, что и Android-клиент: текст — JSON,
 * бинарные сообщения — JPEG-кадры экрана.
 */

const el = (id) => document.getElementById(id);

const ui = {
  code: el('code'),
  pin: el('pin'),
  url: el('url'),
  connectBtn: el('connectBtn'),
  error: el('error'),
  connectPanel: el('connectPanel'),
  sessionPanel: el('sessionPanel'),
  statusChip: el('statusChip'),
  qualityChip: el('qualityChip'),
  peerName: el('peerName'),
  peerInfo: el('peerInfo'),
  canvas: el('screen'),
  overlay: el('overlay'),
  overlayText: el('overlayText'),
  iosNote: el('iosNote'),
  sessionInfo: el('sessionInfo'),
};

const IOS_NOTE =
  'iOS-устройство передаёт экран через ReplayKit, а система iOS не разрешает ' +
  'удалённые касания. Доступны ссылки, буфер обмена и просмотр.';

const state = {
  ws: null,
  pin: '',
  code: '',
  platform: '',
  isIos: false,
  controlAvailable: false,
  connected: false,
  sessionStart: 0,
  frames: 0,
  fps: 0,
  fpsWindowStart: 0,
  latency: 0,
  pingTimer: null,
  infoTimer: null,
  touch: null,
};

const ctx = ui.canvas.getContext('2d');

/** Адрес relay-сервера по умолчанию — тот, с которого открыта страница. */
function defaultUrl() {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}/ws`;
}

/**
 * Параметры ссылки: ?relay=wss://host/ws&code=284913706&pin=4821&auto=1
 * Позволяет открыть готовый сеанс по QR-коду или ссылке из мессенджера.
 */
function applyQueryParams() {
  const params = new URLSearchParams(location.search);
  const relay = params.get('relay');
  const code = params.get('code');
  const pin = params.get('pin');
  if (relay) ui.url.value = relay;
  if (code) ui.code.value = code.replace(/\D/g, '').slice(0, 9);
  if (pin) ui.pin.value = pin.replace(/\D/g, '').slice(0, 4);
  return params.get('auto') === '1';
}

/**
 * Быстрый старт из ссылки: #auto:КОД:PIN.
 * Fragment не отправляется на сервер — код и PIN остаются у клиента.
 */
function applyHashParams() {
  const parts = location.hash.replace(/^#/, '').split(':');
  if (parts[0] !== 'auto' || parts.length < 3) return false;
  ui.code.value = parts[1].replace(/\D/g, '').slice(0, 9);
  ui.pin.value = parts[2].replace(/\D/g, '').slice(0, 4);
  return true;
}

function showError(message) {
  ui.error.textContent = message;
  ui.error.hidden = !message;
}

function setStatus(text, online) {
  ui.statusChip.textContent = text;
  ui.statusChip.classList.toggle('online', Boolean(online));
}

function setOverlay(text, hidden) {
  ui.overlayText.textContent = text;
  ui.overlay.hidden = Boolean(hidden);
}

/** Двузначное число для таймера сеанса. */
function pad2(value) {
  return value < 10 ? '0' + value : String(value);
}

function send(payload) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify(payload));
  }
}

function connect() {
  const code = ui.code.value.replace(/\D/g, '');
  const pin = ui.pin.value.trim();
  showError('');

  if (code.length !== 9) {
    showError('Введите 9-значный код устройства');
    return;
  }
  if (pin.length !== 4) {
    showError('Введите 4-значный PIN-код');
    return;
  }

  state.code = code;
  state.pin = pin;
  setStatus('подключение…', false);
  ui.connectBtn.disabled = true;

  const socket = new WebSocket((ui.url.value || '').trim() || defaultUrl());
  socket.binaryType = 'arraybuffer';
  state.ws = socket;

  socket.addEventListener('open', () => {
    socket.send(JSON.stringify({ t: 'viewer', code, pin }));
  });

  socket.addEventListener('message', (event) => {
    if (typeof event.data === 'string') {
      handleJson(event.data);
    } else {
      renderFrame(event.data);
    }
  });

  socket.addEventListener('close', () => {
    state.connected = false;
    ui.connectBtn.disabled = false;
    stopTimers();
    setStatus('офлайн', false);
    if (!ui.sessionPanel.hidden) {
      setOverlay('Соединение закрыто. Подключитесь снова.', false);
    }
  });

  socket.addEventListener('error', () => {
    showError('Не удалось подключиться к relay-серверу');
    ui.connectBtn.disabled = false;
    setStatus('офлайн', false);
  });
}

function handleJson(raw) {
  let message;
  try {
    message = JSON.parse(raw);
  } catch (error) {
    return;
  }

  switch (message.t) {
    case 'ready':
      send({ t: 'auth', pin: state.pin });
      break;

    case 'auth_ok':
      send({
        t: 'hello',
        name: 'Safari',
        platform: 'iOS',
        model: navigator.userAgent,
        w: 0,
        h: 0,
        control: false,
        pin: false,
      });
      break;

    case 'auth_fail':
      showError('Неверный PIN-код');
      disconnect();
      break;

    case 'hello':
      onPeerHello(message);
      break;

    case 'pong':
      state.latency = Math.max(0, Date.now() - Number(message.ts || 0));
      updateQuality();
      break;

    case 'ping':
      send({ t: 'pong', ts: message.ts });
      break;

    case 'bye':
      setOverlay('Партнёр завершил сеанс', false);
      break;

    case 'peer_left':
      setOverlay('Партнёр отключился', false);
      setStatus('ожидание', false);
      break;

    case 'error':
      showError(mapError(message.reason));
      ui.connectBtn.disabled = false;
      break;

    default:
      break;
  }
}

function mapError(reason) {
  const text = String(reason || '');
  if (text.includes('wrong_pin')) return 'Неверный PIN-код';
  if (text.includes('not_found')) return 'Устройство не найдено';
  if (text.includes('busy')) return 'Устройство уже занято';
  if (text.includes('bad_code')) return 'Некорректный код устройства';
  return text || 'Ошибка подключения';
}

function onPeerHello(message) {
  state.platform = message.platform || 'Android';
  state.isIos = state.platform.toLowerCase().includes('ios');
  state.controlAvailable = Boolean(message.control) || state.isIos;
  state.connected = true;
  state.sessionStart = Date.now();

  ui.peerName.textContent = message.name || 'Устройство';
  ui.peerInfo.textContent = `${state.platform} · защищённое соединение`;
  ui.connectPanel.hidden = true;
  ui.sessionPanel.hidden = false;
  ui.iosNote.hidden = !state.isIos;
  ui.iosNote.textContent = IOS_NOTE;
  setStatus('онлайн', true);

  document.querySelectorAll('.tool.gesture').forEach((button) => {
    button.hidden = state.isIos;
  });

  setOverlay('Ожидаем изображение…', false);
  startTimers();
}

function renderFrame(buffer) {
  const blob = new Blob([buffer], { type: 'image/jpeg' });

  function draw(bitmap) {
    const width = bitmap.width || bitmap.naturalWidth;
    const height = bitmap.height || bitmap.naturalHeight;
    if (ui.canvas.width !== width || ui.canvas.height !== height) {
      ui.canvas.width = width;
      ui.canvas.height = height;
    }
    ctx.drawImage(bitmap, 0, 0);
    if (bitmap.close) bitmap.close();

    if (!state.connected) {
      state.connected = true;
      state.sessionStart = Date.now();
    }
    setOverlay('', true);
    state.frames += 1;
    updateFps();
  }

  function fallback() {
    const url = URL.createObjectURL(blob);
    const image = new Image();
    image.onload = function () {
      draw(image);
      URL.revokeObjectURL(url);
    };
    image.onerror = function () {
      URL.revokeObjectURL(url);
    };
    image.src = url;
  }

  if (typeof createImageBitmap === 'function') {
    createImageBitmap(blob).then(draw, fallback);
  } else {
    fallback();
  }
}

function updateFps() {
  const now = performance.now();
  if (!state.fpsWindowStart) state.fpsWindowStart = now;
  const elapsed = now - state.fpsWindowStart;
  if (elapsed >= 1000) {
    state.fps = Math.round((state.frames * 1000) / elapsed);
    state.frames = 0;
    state.fpsWindowStart = now;
    updateQuality();
  }
}

function updateQuality() {
  ui.qualityChip.textContent = state.fps
    ? `${state.latency} мс · ${state.fps}`
    : `${state.latency} мс`;
}

function startTimers() {
  stopTimers();
  state.pingTimer = setInterval(() => send({ t: 'ping', ts: Date.now() }), 2000);
  state.infoTimer = setInterval(() => {
    if (!state.connected) return;
    const total = Math.floor((Date.now() - state.sessionStart) / 1000);
    const time = pad2(Math.floor(total / 60)) + ':' + pad2(total % 60);
    ui.sessionInfo.textContent = `${time} · ${state.fps} к/с · ${state.latency} мс`;
  }, 1000);
}

function stopTimers() {
  if (state.pingTimer) clearInterval(state.pingTimer);
  if (state.infoTimer) clearInterval(state.infoTimer);
  state.pingTimer = null;
  state.infoTimer = null;
}

function disconnect() {
  stopTimers();
  if (state.ws) {
    try {
      if (state.ws.readyState === WebSocket.OPEN) send({ t: 'bye' });
      state.ws.close();
    } catch (error) {
      /* уже закрыт */
    }
  }
  state.ws = null;
  state.connected = false;
  ui.connectBtn.disabled = false;
  ui.sessionPanel.hidden = true;
  ui.connectPanel.hidden = false;
  setStatus('офлайн', false);
}

/** Координаты касания в нормализованном виде (0..1) с учётом масштаба canvas. */
function normalizedPoint(event) {
  const rect = ui.canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  const x = (event.clientX - rect.left) / rect.width;
  const y = (event.clientY - rect.top) / rect.height;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return { x, y };
}

function setupTouch() {
  const canvas = ui.canvas;

  function onDown(event) {
    if (state.isIos || !state.controlAvailable) return;
    const point = normalizedPoint(event);
    if (!point) return;
    state.touch = { x: point.x, y: point.y, at: performance.now(), moved: false };
    if (canvas.setPointerCapture && event.pointerId !== undefined) {
      canvas.setPointerCapture(event.pointerId);
    }
  }

  function onMove(event) {
    const touch = state.touch;
    if (!touch) return;
    const point = normalizedPoint(event);
    if (!point) return;
    if (Math.abs(point.x - touch.x) > 0.02 || Math.abs(point.y - touch.y) > 0.02) {
      touch.moved = true;
    }
  }

  function onUp(event) {
    const touch = state.touch;
    state.touch = null;
    if (!touch) return;
    const point = normalizedPoint(event);
    if (!point) return;
    const duration = Math.round(performance.now() - touch.at);
    if (!touch.moved && duration < 320) {
      send({ t: 'tap', x: point.x, y: point.y });
    } else {
      send({
        t: 'swipe',
        x1: touch.x,
        y1: touch.y,
        x2: point.x,
        y2: point.y,
        ms: Math.min(900, Math.max(80, duration)),
      });
    }
  }

  function onCancel() {
    state.touch = null;
  }

  if (window.PointerEvent) {
    canvas.addEventListener('pointerdown', onDown);
    canvas.addEventListener('pointermove', onMove);
    canvas.addEventListener('pointerup', onUp);
    canvas.addEventListener('pointercancel', onCancel);
    return;
  }

  // Старые WebView (Android < 7.1, Chrome < 55) знают только touch-события.
  canvas.addEventListener('touchstart', function (event) {
    event.preventDefault();
    onDown(event.touches[0]);
  }, false);
  canvas.addEventListener('touchmove', function (event) {
    event.preventDefault();
    onMove(event.touches[0]);
  }, false);
  canvas.addEventListener('touchend', function (event) {
    event.preventDefault();
    onUp(event.changedTouches[0]);
  }, false);
  canvas.addEventListener('touchcancel', onCancel, false);
}

function askText(title, placeholder) {
  const value = window.prompt(title, placeholder || '');
  return value === null ? null : value.trim();
}

function handleTool(action) {
  if (!state.connected) return;

  switch (action) {
    case 'keyboard': {
      const text = askText('Текст для партнёра');
      if (text) send({ t: 'text', s: text });
      break;
    }
    case 'back':
      send({ t: 'key', k: 'back' });
      break;
    case 'home':
      send({ t: 'key', k: 'home' });
      break;
    case 'recents':
      send({ t: 'key', k: 'recents' });
      break;
    case 'link': {
      const url = askText('Адрес для браузера партнёра', 'https://');
      if (!url) break;
      if (!/^https?:\/\//i.test(url)) {
        window.alert('Адрес должен начинаться с http:// или https://');
        break;
      }
      send({ t: 'cmd', action: 'open_url', value: url });
      break;
    }
    case 'clipboard': {
      const text = askText('Текст в буфер обмена партнёра');
      if (text) send({ t: 'cmd', action: 'clipboard', value: text });
      break;
    }
    case 'shot':
      snapshot();
      break;
    case 'end':
      disconnect();
      break;
    default:
      break;
  }
}

function snapshot() {
  if (!ui.canvas.width) return;
  ui.canvas.toBlob((blob) => {
    if (!blob) return;
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `lumen-${Date.now()}.jpg`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 4000);
  }, 'image/jpeg', 0.95);
}

function init() {
  ui.url.placeholder = defaultUrl();
  ui.connectBtn.addEventListener('click', connect);
  ui.pin.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') connect();
  });
  document.querySelectorAll('.tool').forEach((button) => {
    button.addEventListener('click', () => handleTool(button.dataset.action));
  });
  setupTouch();
  setStatus('офлайн', false);
  const autoFromQuery = applyQueryParams();
  const autoFromHash = applyHashParams();
  if (autoFromQuery || autoFromHash) connect();
}

init();
