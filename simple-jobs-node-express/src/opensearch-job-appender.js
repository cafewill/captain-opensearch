'use strict';
const https = require('https');
const http  = require('http');
const os    = require('os');

const KST_OFFSET = 9 * 60; // UTC+9

function _toOffsetIso(date, offsetMinutes) {
  const shifted = new Date(date.getTime() + offsetMinutes * 60000);
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const hh   = String(Math.floor(Math.abs(offsetMinutes) / 60)).padStart(2, '0');
  const mm   = String(Math.abs(offsetMinutes) % 60).padStart(2, '0');
  return shifted.toISOString().replace('Z', `${sign}${hh}:${mm}`);
}

function _normalizeOperation(operation) {
  const value = String(operation || '').trim().toLowerCase();
  return value === 'index' || value === 'create' ? value : 'create';
}

function _bulkUrl(url) {
  const base = String(url || '').replace(/\/+$/, '');
  return base.endsWith('/_bulk') ? base : `${base}/_bulk`;
}

/**
 * OpenSearch _bulk API 어펜더 — Node.js 내장 모듈만 사용 (추가 의존성 없음)
 *
 * 다른 프로젝트 반영 시 이 파일 하나만 복사
 *   1. src/ 에 복사
 *   2. .env 에 OPENSEARCH_* 환경변수 추가
 *   3. require('./opensearch-appender') 후 인스턴스 생성
 */
class OpenSearchJobAppender {
  constructor({
    url                  = 'https://localhost:9200',
    username             = '',
    password             = '',
    app                  = 'app',
    env                  = 'local',
    maxBatchBytes        = 1_000_000,
    flushIntervalSeconds = 1,
    queueSize            = 8192,
    operation            = 'create',
    trustAllSsl          = true,
    timeout              = 10,
    maxRetries           = 3,
    headers              = {},
    persistentWriterThread = true,
  } = {}) {
    this._index      = `logs-${app}`;
    this._app        = app;
    this._env        = env;
    this._instanceId = process.env.HOSTNAME || os.hostname();
    this._maxBytes   = maxBatchBytes;
    this._maxQueue   = queueSize;
    this._operation  = _normalizeOperation(operation);
    this._trustAllSsl = trustAllSsl;
    this._timeoutMs  = timeout * 1000;
    this._maxRetries = Math.max(0, maxRetries);
    this._headers    = headers || {};
    this._persistentWriterThread = persistentWriterThread;
    this._queue      = [];
    this._auth       = username
      ? Buffer.from(`${username}:${password}`).toString('base64')
      : null;
    this._parsed = new URL(_bulkUrl(url));
    this._lib    = this._parsed.protocol === 'https:' ? https : http;
    this._flushIntervalMs = flushIntervalSeconds * 1000;
    this._timer = null;
    if (this._persistentWriterThread) this._startWriter();
  }

  log(level, message, extra = {}) {
    if (this._queue.length >= this._maxQueue) {
      console.warn(`[OpenSearch] 큐 포화 — 로그 유실 (현재: ${this._queue.length}/${this._maxQueue})`);
      return;
    }
    const now   = new Date();
    const today = now.toISOString().slice(0, 10).replace(/-/g, '.');
    const localOffset = -now.getTimezoneOffset(); // getTimezoneOffset() 은 UTC-local 이므로 부호 반전
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
    if (!this._persistentWriterThread) this._startWriter();
  }

  stop() {
    if (this._timer) clearInterval(this._timer);
    this._timer = null;
    this._flush();
  }

  _startWriter() {
    if (this._timer) return;
    this._timer = setInterval(() => this._flush(), this._flushIntervalMs);
    this._timer.unref();
  }

  _stopWriterIfIdle() {
    if (this._persistentWriterThread || !this._timer || this._queue.length > 0) return;
    clearInterval(this._timer);
    this._timer = null;
  }

  _flush() {
    if (this._queue.length === 0) {
      this._stopWriterIfIdle();
      return;
    }
    const items = this._queue.splice(0);
    let bulk = '';
    let batchItems = [];
    for (const item of items) {
      const meta    = this._actionLine(item.index);
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
    this._stopWriterIfIdle();
  }

  _actionLine(index) {
    return JSON.stringify({ [this._operation]: { _index: index } }) + '\n';
  }

  _buildPayload(items) {
    return items.map((item) => this._actionLine(item.index) + JSON.stringify(item.doc) + '\n').join('');
  }

  _send(body, items, attempt = 0) {
    const buf  = Buffer.from(body, 'utf8');
    const opts = {
      hostname: this._parsed.hostname,
      port:     this._parsed.port,
      path:     this._parsed.pathname,
      method:   'POST',
      headers: {
        'Content-Type':   'application/x-ndjson',
        Accept:           'application/json',
        'Content-Length': buf.length,
        ...this._headers,
      },
      rejectUnauthorized: !this._trustAllSsl,
      timeout: this._timeoutMs,
    };
    if (this._auth) opts.headers['Authorization'] = `Basic ${this._auth}`;
    const req = this._lib.request(opts, (res) => {
      let responseBody = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { responseBody += chunk; });
      res.on('end', () => {
        if (res.statusCode === 429 || res.statusCode >= 500) {
          this._retryOrRequeue(items, `HTTP ${res.statusCode}: ${responseBody}`, attempt);
          return;
        }
        if (res.statusCode >= 400) {
          console.warn(`[OpenSearch] 전송 실패: HTTP ${res.statusCode}: ${responseBody}`);
          return;
        }
        const result = this._analyzeBulkResponse(items, responseBody);
        if (result.success) return;
        if (result.retryable.length > 0) {
          this._retryOrRequeue(result.retryable, result.message, attempt);
          return;
        }
        console.warn(`[OpenSearch] 전송 실패: ${result.message}`);
      });
    });
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', (err) => {
      this._retryOrRequeue(items, err.message, attempt);
    });
    req.write(buf);
    req.end();
  }

  _retryOrRequeue(items, message, attempt) {
    if (attempt < this._maxRetries) {
      setTimeout(() => this._send(this._buildPayload(items), items, attempt + 1), 100);
      return;
    }
    if (this._queue.length + items.length <= this._maxQueue) {
      this._queue.unshift(...items);
      console.warn(`[OpenSearch] 전송 실패, ${items.length}건 재큐: ${message}`);
      if (!this._persistentWriterThread) this._startWriter();
    } else {
      console.error(`[OpenSearch] 전송 실패, ${items.length}건 유실 (큐 포화): ${message}`);
    }
  }

  _analyzeBulkResponse(items, body) {
    if (!body) return { success: true, retryable: [], message: '' };
    let root;
    try {
      root = JSON.parse(body);
    } catch (_) {
      return { success: true, retryable: [], message: '' };
    }
    if (!root.errors) return { success: true, retryable: [], message: '' };

    const retryable = [];
    const fatal = [];
    for (let i = 0; i < (root.items || []).length && i < items.length; i += 1) {
      const node = root.items[i].index || root.items[i].create || {};
      const status = node.status || 200;
      if (status === 429 || status >= 500) {
        retryable.push(items[i]);
      } else if (status >= 400) {
        const error = node.error || {};
        fatal.push(`[${status}] ${error.type || 'unknown'}: ${error.reason || 'unknown'}`);
      }
    }
    if (retryable.length > 0) return { success: false, retryable, message: `partial retryable: ${fatal.join(' ')}` };
    if (fatal.length > 0) return { success: false, retryable: [], message: fatal.join(' ') };
    return { success: true, retryable: [], message: '' };
  }
}

module.exports = OpenSearchJobAppender;
