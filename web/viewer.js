// Browser half of the scrcpy H.264 relay. No credentials are persisted.
const MAX_PACKET = 2 * 1024 * 1024;
const MAX_QUEUE = 8;

export function annexBNalus(data) {
  const units = [];
  let start = -1;
  for (let i = 0; i + 2 < data.length; i++) {
    if (data[i] !== 0 || data[i + 1] !== 0) continue;
    const prefix = data[i + 2] === 1 ? 3 : data[i + 2] === 0 && data[i + 3] === 1 ? 4 : 0;
    if (!prefix) continue;
    if (start >= 0 && i > start) units.push(data.subarray(start, i));
    start = i + prefix;
    i = start - 1;
  }
  if (start >= 0 && start < data.length) units.push(data.subarray(start));
  return units;
}

function sameBytes(a, b) {
  return !!a && a.length === b.length && a.every((value, index) => value === b[index]);
}

export function touchPoint(rect, size, clientX, clientY) {
  if (!(rect.width > 0 && rect.height > 0 && size.w > 0 && size.h > 0)) return null;
  return {
    x: Math.max(0, Math.min(size.w - 1, Math.floor((clientX - rect.left) / rect.width * size.w))),
    y: Math.max(0, Math.min(size.h - 1, Math.floor((clientY - rect.top) / rect.height * size.h))),
  };
}

export function friendlyError(code) {
  return ({
    phone_stream_in_use: '手机正在被另一处控制，等那边停止后再连接。',
    device_offline: '手机暂时不在线，请检查手机的网络和后台状态。',
    device_unavailable: '还没有可连接的手机，请先在手机伙伴里完成配对。',
    device_not_paired: '还没有可连接的手机，请先完成配对。',
    device_capability_missing: '手机还没开启这项能力；实时画面需要无障碍和 Root 扩展。',
    stream_unsupported: '手机尚未开启实时投屏能力。',
    command_queue_busy: '手机还有一个操作没完成，稍后再连接。',
    command_pending: '手机还有一个操作没完成，稍后再连接。',
    secure_keyguard_present: '手机有密码锁，请先在手机上解锁。',
    unauthorized: '控制密钥不正确或已失效，请重新读取自己的 operator.token。',
    codec_unsupported: '这个浏览器无法解码手机画面，请使用支持 WebCodecs 的新版本浏览器。',
    stream_root_denied: '手机伙伴没拿到 Root：请在 Root 管理器里给「AI 手机伙伴」永久授权，再重新连接。',
    stream_server_failed: '手机上的投屏组件（scrcpy）没能启动，这台手机的系统可能不兼容。',
    stream_connect_timeout: '投屏组件已启动，但 10 秒内没连上手机伙伴，请重新连接。',
    stream_capture_failed: '投屏中途出错了，请重新连接；反复出现请看教程里的常见问题。',
    root_disabled: '手机还没有开启 Root 扩展。',
    stream_timeout: '连接超时，已经停止本次控制。',
  })[code] || `暂时没能接上手机${code ? `（${code}）` : ''}。`;
}

export class PhoneViewer {
  constructor({ canvas, base, token, update, resized, failure }) {
    Object.assign(this, { canvas, base, token, update, resized, failure });
    this.closed = false;
    this.failed = false;
    this.session = null;
    this.opening = Promise.resolve();
    this.cleanup = null;
    this.socket = null;
    this.authenticated = false;
    this.keepalive = null;
    this.lastMessage = Date.now();
    this.decoder = null;
    this.decoderConfig = null;
    this.generation = 0;
    this.sps = this.pps = null;
    this.needKeyframe = true;
    this.resetAt = 0;
    this.resetTimer = null;
    this.dimensions = null;
    this.fingers = new Map();
    this.ready = false;
  }

  start() { this.opening = this.open(); }

  fail(message) {
    if (this.closed || this.failed) return;
    this.cancelTouches();
    this.failed = true;
    this.ready = false;
    this.failure(message);
  }

  async open() {
    if (!globalThis.VideoDecoder || !globalThis.EncodedVideoChunk || !globalThis.isSecureContext) {
      this.fail('实时画面需要 HTTPS（或 localhost）和支持 WebCodecs 的浏览器；也可以先查看单张截图。');
      return;
    }
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), 20_000);
    try {
      const response = await fetch(`${this.base}/stream`, {
        method: 'POST', headers: { Authorization: `Bearer ${this.token}`, 'Content-Type': 'application/json' },
        body: '{}', signal: abort.signal, cache: 'no-store',
      });
      const result = await response.json();
      if (!response.ok || !result.ok) { this.fail(friendlyError(result.error || '')); return; }
      this.session = result.session;
      // A navigation may finish before the POST. Keep its id so stop can delete it.
      if (this.closed) return;
      const apiUrl = new URL(this.base, location.href);
      const viewerUrl = new URL(this.session.viewer_path, apiUrl);
      if (viewerUrl.origin !== apiUrl.origin) throw new Error('unexpected_viewer_origin');
      viewerUrl.protocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = this.socket = new WebSocket(viewerUrl);
      socket.binaryType = 'arraybuffer';
      socket.onopen = () => {
        if (!this.closed) socket.send(JSON.stringify({ type: 'auth', token: this.session.viewer_token }));
      };
      socket.onmessage = event => {
        if (this.closed || this.failed) return;
        this.lastMessage = Date.now();
        try {
          if (typeof event.data === 'string') this.message(JSON.parse(event.data));
          else if (event.data instanceof ArrayBuffer && this.authenticated) this.packet(event.data);
        } catch { this.fail('画面数据没能读出来，请重新连接。'); }
      };
      socket.onerror = socket.onclose = () => this.fail('连接中断，本次控制已停止。');
      this.keepalive = setInterval(() => {
        if (Date.now() - this.lastMessage > 30_000) this.fail('手机连接没有回应，本次控制已停止。');
        else this.send({ type: 'ping' });
      }, 10_000);
    } catch { this.fail('暂时连不上服务，请检查地址、HTTPS 和网络。'); }
    finally { clearTimeout(timeout); }
  }

  message(message) {
    if (message.type === 'status') {
      this.authenticated = true;
      if (!this.ready) this.update('waiting', message.state === 'connected' ? '手机连上了，等第一帧画面…' : '正在叫手机，待命时会慢一点…');
    } else if (message.type === 'video') {
      if (message.codec !== 'h264') { this.fail(friendlyError('codec_unsupported')); return; }
      this.cancelTouches();
      this.ready = false;
      this.needKeyframe = true;
      if (this.decoder?.state === 'configured' && this.decoderConfig) {
        this.decoder.reset(); this.decoder.configure(this.decoderConfig);
      }
      this.canvas.getContext('2d', { alpha: false })?.clearRect(0, 0, this.canvas.width, this.canvas.height);
      this.update('waiting', '正在接上画面…');
    } else if (message.type === 'error') this.fail(friendlyError(message.error));
    else if (message.type === 'stopped') this.fail('本次连接已经结束。');
  }

  send(message) {
    if (this.closed || !this.authenticated || this.socket?.readyState !== WebSocket.OPEN) return false;
    if (this.socket.bufferedAmount > 64 * 1024) { this.fail('网络跟不上，已停止本次控制，请重新连接。'); return false; }
    try { this.socket.send(JSON.stringify(message)); return true; }
    catch { this.fail('连接已经断开。'); return false; }
  }

  requestKeyframe() {
    if (this.closed || this.failed || !this.needKeyframe) return;
    const remaining = 1500 - (Date.now() - this.resetAt);
    if (remaining > 0) {
      if (this.resetTimer === null) this.resetTimer = setTimeout(() => {
        this.resetTimer = null; if (this.needKeyframe) this.requestKeyframe();
      }, remaining);
      return;
    }
    if (this.resetTimer !== null) clearTimeout(this.resetTimer);
    this.resetTimer = null;
    this.resetAt = Date.now();
    this.send({ type: 'reset_video' });
  }

  closeDecoder() {
    this.generation++;
    if (this.decoder && this.decoder.state !== 'closed') this.decoder.close();
    this.decoder = null;
    this.needKeyframe = true;
    this.cancelTouches();
    this.ready = false;
  }

  async configure() {
    this.closeDecoder();
    if (!this.sps || this.sps.length < 4 || !this.pps) return;
    this.update('waiting', '正在接上画面…');
    const generation = this.generation;
    const config = {
      codec: 'avc1.' + Array.from(this.sps.subarray(1, 4), v => v.toString(16).padStart(2, '0')).join(''),
      optimizeForLatency: true,
    };
    try {
      const supported = await VideoDecoder.isConfigSupported(config);
      if (this.closed || this.failed || generation !== this.generation) return;
      if (!supported.supported) { this.fail(friendlyError('codec_unsupported')); return; }
      this.decoder = new VideoDecoder({
        output: frame => {
          try {
            if (this.closed || this.failed || generation !== this.generation) return;
            const w = frame.displayWidth, h = frame.displayHeight;
            if (!w || !h) return;
            const context = this.canvas.getContext('2d', { alpha: false });
            if (!context) { this.fail('这个浏览器没能显示手机画面。'); return; }
            if (this.dimensions?.w !== w || this.dimensions.h !== h) {
              this.cancelTouches(); this.dimensions = { w, h };
              this.canvas.width = w; this.canvas.height = h; this.resized({ w, h });
            }
            context.drawImage(frame, 0, 0, w, h);
            if (!this.ready) { this.ready = true; this.update('live', '已连接。点按、滑动和长按会直接落在手机上。'); }
          } catch { this.fail('画面暂时没能显示出来。'); }
          finally { frame.close(); }
        },
        error: () => { if (generation === this.generation) this.fail('画面解码中断，请重新连接。'); },
      });
      this.decoder.configure(config);
      this.decoderConfig = config;
      this.requestKeyframe();
    } catch { if (generation === this.generation) this.fail(friendlyError('codec_unsupported')); }
  }

  packet(buffer) {
    if (buffer.byteLength < 10 || buffer.byteLength > MAX_PACKET) throw new Error('invalid_packet');
    const bytes = new Uint8Array(buffer), flags = bytes[0], payload = bytes.subarray(9);
    if (flags & ~3) throw new Error('invalid_flags');
    let changed = false;
    if (flags & 3) for (const unit of annexBNalus(payload)) {
      const type = unit[0] & 31;
      if (type === 7 && !sameBytes(this.sps, unit)) { this.sps = unit.slice(); changed = true; }
      if (type === 8 && !sameBytes(this.pps, unit)) { this.pps = unit.slice(); changed = true; }
    }
    if (changed) void this.configure();
    if ((flags & 1) && !(flags & 2)) return;
    const decoder = this.decoder;
    if (!decoder || decoder.state !== 'configured') return;
    const key = !!(flags & 2);
    if (decoder.decodeQueueSize > MAX_QUEUE) {
      if (!key || !this.decoderConfig || !this.sps || !this.pps) { void this.configure(); return; }
      this.cancelTouches(); this.ready = false; this.needKeyframe = true;
      this.update('waiting', '网络有点慢，正在接回画面…');
      try { decoder.reset(); decoder.configure(this.decoderConfig); }
      catch { void this.configure(); return; }
    }
    if (this.needKeyframe && !key) { this.requestKeyframe(); return; }
    const header = new DataView(buffer);
    const timestamp = header.getUint32(1) * 4294967296 + header.getUint32(5);
    if (!Number.isSafeInteger(timestamp)) throw new Error('invalid_timestamp');
    let data = payload;
    if (key) {
      if (!this.sps || !this.pps) return;
      data = new Uint8Array(8 + this.sps.length + this.pps.length + payload.length);
      data.set([0, 0, 0, 1]); data.set(this.sps, 4);
      const ppsStart = 4 + this.sps.length;
      data.set([0, 0, 0, 1], ppsStart); data.set(this.pps, ppsStart + 4);
      data.set(payload, ppsStart + 4 + this.pps.length);
    }
    try {
      decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp, data }));
      this.needKeyframe = false;
      if (key && this.resetTimer !== null) { clearTimeout(this.resetTimer); this.resetTimer = null; }
    } catch { void this.configure(); }
  }

  pointer(action, browserId, clientX, clientY) {
    if (!this.ready || !this.dimensions) return false;
    const point = touchPoint(this.canvas.getBoundingClientRect(), this.dimensions, clientX, clientY);
    if (!point) return false;
    let touch = this.fingers.get(browserId);
    if (action === 0) {
      if (touch || this.fingers.size >= 10) return false;
      const used = new Set(Array.from(this.fingers.values(), value => value.pointer_id));
      let pointer_id = 0; while (used.has(pointer_id)) pointer_id++;
      touch = { pointer_id, width: this.dimensions.w, height: this.dimensions.h };
      this.fingers.set(browserId, touch);
    }
    if (!touch) return false;
    Object.assign(touch, point);
    const sent = this.send({ type: 'touch', action, ...touch });
    if (action === 1 || !sent) this.fingers.delete(browserId);
    return sent;
  }

  cancelPointer(browserId) { if (this.fingers.has(browserId)) this.cancelTouches(); }
  cancelTouches() {
    const touch = this.fingers.values().next().value;
    this.fingers.clear();
    if (touch) this.send({ type: 'touch', action: 3, ...touch });
  }
  key(keycode) { if (this.ready) { this.cancelTouches(); this.send({ type: 'key', keycode }); } }
  text(text) { return this.ready && text.length > 0 && this.send({ type: 'text', text: text.slice(0, 2000) }); }

  stop() {
    if (this.cleanup) return this.cleanup;
    this.cancelTouches(); this.closed = true; this.closeDecoder();
    clearInterval(this.keepalive); clearTimeout(this.resetTimer);
    if (this.socket) {
      this.socket.onopen = this.socket.onmessage = this.socket.onerror = this.socket.onclose = null;
      this.socket.close(); this.socket = null;
    }
    this.cleanup = this.opening.then(async () => {
      if (!this.session) return;
      const abort = new AbortController(), timeout = setTimeout(() => abort.abort(), 5000);
      try {
        await fetch(`${this.base}/stream/${encodeURIComponent(this.session.id)}`, {
          method: 'DELETE', headers: { Authorization: `Bearer ${this.token}` },
          keepalive: true, signal: abort.signal, cache: 'no-store',
        });
      } catch { /* Closed sockets and unattached-session expiry also stop the relay. */ }
      finally { clearTimeout(timeout); this.token = ''; this.session = null; }
    });
    return this.cleanup;
  }
}
