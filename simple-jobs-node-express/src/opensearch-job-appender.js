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
      bulk += JSON.stringify({ index: { _index: item.index } }) + '\n';
      bulk += JSON.stringify(item.doc) + '\n';
      batchItems.push(item);
      if (Buffer.byteLength(bulk) >= this._maxBytes) {
        this._send(bulk, batchItems);
        bulk = '';
        batchItems = [];
      }
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

module.exports = OpenSearchJobAppender;
