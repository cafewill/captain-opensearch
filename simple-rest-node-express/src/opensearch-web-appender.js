'use strict';
const https = require('https');
const http  = require('http');
const os    = require('os');
const { randomUUID } = require('crypto');

const KST_OFFSET = 9 * 60;

function _toOffsetIso(date, offsetMinutes) {
  const shifted = new Date(date.getTime() + offsetMinutes * 60000);
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const hh   = String(Math.floor(Math.abs(offsetMinutes) / 60)).padStart(2, '0');
  const mm   = String(Math.abs(offsetMinutes) % 60).padStart(2, '0');
  return shifted.toISOString().replace('Z', `${sign}${hh}:${mm}`);
}

/**
 * OpenSearch _bulk API 웹 어펜더 (REST API 서비스용)
 * - HTTP 요청마다 trace_id · http_method · http_path · client_ip · http_status · duration_ms 추적
 * - 요청 완료 시 access log 자동 발행
 * - 추가 의존성 없음 (Node.js 내장 모듈만 사용)
 */
class OpenSearchWebAppender {
  constructor({
    scheme               = 'https',
    host                 = 'localhost',
    port                 = 9200,
    username             = '',
    password             = '',
    app                  = 'app',
    env                  = 'local',
    maxBatchBytes        = 1_000_000,
    flushIntervalSeconds = 1,
    queueSize            = 8192,
  } = {}) {
    this._index      = `logs-${app}`;
    this._app        = app;
    this._env        = env;
    this._instanceId = process.env.HOSTNAME || os.hostname();
    this._maxBytes   = maxBatchBytes;
    this._maxQueue   = queueSize;
    this._queue      = [];
    this._auth       = username
      ? Buffer.from(`${username}:${password}`).toString('base64')
      : null;
    this._lib    = scheme === 'https' ? https : http;
    this._parsed = new URL(`${scheme}://${host}:${port}/_bulk`);
    this._timer  = setInterval(() => this._flush(), flushIntervalSeconds * 1000);
    this._timer.unref();
  }

  log(level, message, extra = {}) {
    if (this._queue.length >= this._maxQueue) {
      console.warn(`[OpenSearch] 큐 포화 — 로그 유실 (현재: ${this._queue.length}/${this._maxQueue})`);
      return;
    }
    const now   = new Date();
    const today = now.toISOString().slice(0, 10).replace(/-/g, '.');
    const localOffset = -now.getTimezoneOffset();
    this._queue.push({
      index: `${this._index}-${today}`,
      doc: {
        '@timestamp':       now.toISOString(),
        '@timestamp_local': _toOffsetIso(now, localOffset),
        '@timestamp_kst':   _toOffsetIso(now, KST_OFFSET),
        level,
        message,
        app:         this._app,
        env:         this._env,
        instance_id: this._instanceId,
        ...extra,
      },
    });
  }

  /** Express 미들웨어 — req/res 에 traceId 주입, 응답 후 access log 발행 */
  middleware() {
    return (req, res, next) => {
      const traceId = req.headers['x-request-id'] || randomUUID();
      req.traceId = traceId;
      res.setHeader('X-Request-ID', traceId);

      const start = Date.now();
      res.on('finish', () => {
        const duration = Date.now() - start;
        const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
        console.log(`[${ts}] ${req.method} ${req.path || req.url} → ${res.statusCode} (${duration}ms) [${traceId}]`);
        this.log('INFO', `${req.method} ${req.path} → ${res.statusCode}`, {
          trace_id:    traceId,
          http_method: req.method,
          http_path:   req.path || req.url,
          client_ip:   (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim(),
          http_status: res.statusCode,
          duration_ms: duration,
        });
      });
      next();
    };
  }

  stop() {
    clearInterval(this._timer);
    this._flush();
  }

  _flush() {
    if (this._queue.length === 0) return;
    const items = this._queue.splice(0);
    let bulk = '';
    let batchItems = [];
    for (const item of items) {
      const meta    = JSON.stringify({ index: { _index: item.index } }) + '\n';
      const docLine = JSON.stringify(item.doc) + '\n';
      const addition = Buffer.byteLength(meta + docLine);
      // 추가 전 초과 여부 확인 — 배치에 내용이 있을 때만 먼저 전송
      if (bulk && Buffer.byteLength(bulk) + addition > this._maxBytes) {
        this._send(bulk, batchItems);
        bulk = '';
        batchItems = [];
      }
      bulk += meta + docLine;
      batchItems.push(item);
    }
    if (bulk) this._send(bulk, batchItems);
  }

  _send(body, items) {
    const buf  = Buffer.from(body, 'utf8');
    const opts = {
      hostname: this._parsed.hostname,
      port:     this._parsed.port,
      path:     this._parsed.pathname,
      method:   'POST',
      headers: {
        'Content-Type':   'application/json; charset=UTF-8',
        'Content-Length': buf.length,
      },
      rejectUnauthorized: false,
    };
    if (this._auth) opts.headers['Authorization'] = `Basic ${this._auth}`;
    const req = this._lib.request(opts, (res) => { res.resume(); });
    req.on('error', (err) => {
      if (this._queue.length + items.length <= this._maxQueue) {
        this._queue.unshift(...items);
        console.warn(`[OpenSearch] 전송 실패, ${items.length}건 재큐: ${err.message}`);
      } else {
        console.error(`[OpenSearch] 전송 실패, ${items.length}건 유실 (큐 포화): ${err.message}`);
      }
    });
    req.write(buf);
    req.end();
  }
}

module.exports = OpenSearchWebAppender;
