import * as https from 'https';
import * as http  from 'http';
import * as os    from 'os';

const KST_OFFSET = 9 * 60;

function toOffsetIso(date: Date, offsetMinutes: number): string {
  const shifted = new Date(date.getTime() + offsetMinutes * 60000);
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const hh   = String(Math.floor(Math.abs(offsetMinutes) / 60)).padStart(2, '0');
  const mm   = String(Math.abs(offsetMinutes) % 60).padStart(2, '0');
  return shifted.toISOString().replace('Z', `${sign}${hh}:${mm}`);
}

function normalizeOperation(operation?: string): 'index' | 'create' {
  const value = String(operation || '').trim().toLowerCase();
  return value === 'index' || value === 'create' ? value : 'create';
}

function bulkUrl(url: string): string {
  const base = String(url || '').replace(/\/+$/, '');
  return base.endsWith('/_bulk') ? base : `${base}/_bulk`;
}

/**
 * OpenSearch _bulk API 어펜더 — Node.js 내장 모듈만 사용 (추가 의존성 없음)
 *
 * 다른 프로젝트 반영 시 이 파일 하나만 복사
 *   1. src/ 에 복사 (패키지명 불필요, 클래스 그대로 사용)
 *   2. .env 에 OPENSEARCH_* 환경변수 추가
 *   3. import { OpenSearchJobAppender } from './opensearch.job-appender' 후 인스턴스 생성
 */

export interface OpenSearchJobAppenderConfig {
  url?:                  string;
  username?:             string;
  password?:             string;
  app?:                  string;
  env?:                  string;
  maxBatchBytes?:        number;
  flushIntervalSeconds?: number;
  queueSize?:            number;
  operation?:            string;
  trustAllSsl?:          boolean;
  timeout?:              number;
  maxRetries?:           number;
  headers?:              Record<string, string>;
  persistentWriterThread?: boolean;
}

interface QueueEntry {
  index: string;
  doc:   Record<string, unknown>;
}

export class OpenSearchJobAppender {
  private readonly index:      string;
  private readonly app:        string;
  private readonly env:        string;
  private readonly instanceId: string;
  private readonly maxBytes:   number;
  private readonly maxQueue:   number;
  private readonly operation:  'index' | 'create';
  private readonly trustAllSsl: boolean;
  private readonly timeoutMs:  number;
  private readonly maxRetries: number;
  private readonly headers:    Record<string, string>;
  private readonly persistentWriterThread: boolean;
  private readonly auth:       string | null;
  private readonly lib:        typeof https | typeof http;
  private readonly parsed:     URL;
  private readonly queue:      QueueEntry[] = [];
  private readonly flushIntervalMs: number;
  private timer:             NodeJS.Timeout | null = null;

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
  }: OpenSearchJobAppenderConfig = {}) {
    this.index      = `logs-${app}`;
    this.app        = app;
    this.env        = env;
    this.instanceId = process.env.HOSTNAME || os.hostname();
    this.maxBytes   = maxBatchBytes;
    this.maxQueue   = queueSize;
    this.operation  = normalizeOperation(operation);
    this.trustAllSsl = trustAllSsl;
    this.timeoutMs  = timeout * 1000;
    this.maxRetries = Math.max(0, maxRetries);
    this.headers    = headers;
    this.persistentWriterThread = persistentWriterThread;
    this.auth       = username
      ? Buffer.from(`${username}:${password}`).toString('base64')
      : null;
    this.parsed = new URL(bulkUrl(url));
    this.lib    = this.parsed.protocol === 'https:' ? https : http;
    this.flushIntervalMs = flushIntervalSeconds * 1000;
    if (this.persistentWriterThread) this.startWriter();
  }

  log(level: string, message: string, extra: Record<string, unknown> = {}): void {
    if (this.queue.length >= this.maxQueue) {
      console.warn(`[OpenSearch] 큐 포화 — 로그 유실 (현재: ${this.queue.length}/${this.maxQueue})`);
      return;
    }
    const now   = new Date();
    const today = now.toISOString().slice(0, 10).replace(/-/g, '.');
    const localOffset = -now.getTimezoneOffset();
    this.queue.push({
      index: `${this.index}-${today}`,
      doc: {
        '@timestamp':       now.toISOString(),
        '@timestamp_local': toOffsetIso(now, localOffset),
        '@timestamp_kst':   toOffsetIso(now, KST_OFFSET),
        level,
        message,
        app:         this.app,
        env:         this.env,
        instance_id: this.instanceId,
        ...extra,
      },
    });
    if (!this.persistentWriterThread) this.startWriter();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.flush();
  }

  private startWriter(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.flush(), this.flushIntervalMs);
    this.timer.unref();
  }

  private stopWriterIfIdle(): void {
    if (this.persistentWriterThread || !this.timer || this.queue.length > 0) return;
    clearInterval(this.timer);
    this.timer = null;
  }

  private flush(): void {
    if (this.queue.length === 0) {
      this.stopWriterIfIdle();
      return;
    }
    const items = this.queue.splice(0);
    let bulk = '';
    let batchItems: QueueEntry[] = [];
    for (const item of items) {
      const meta    = this.actionLine(item.index);
      const docLine = JSON.stringify(item.doc) + '\n';
      const addition = Buffer.byteLength(meta + docLine);
      // 추가 전 초과 여부 확인 — 배치에 내용이 있을 때만 먼저 전송
      if (bulk && Buffer.byteLength(bulk) + addition > this.maxBytes) {
        this.send(bulk, batchItems);
        bulk = '';
        batchItems = [];
      }
      bulk += meta + docLine;
      batchItems.push(item);
    }
    if (bulk) this.send(bulk, batchItems);
    this.stopWriterIfIdle();
  }

  private actionLine(index: string): string {
    return JSON.stringify({ [this.operation]: { _index: index } }) + '\n';
  }

  private buildPayload(items: QueueEntry[]): string {
    return items.map((item) => this.actionLine(item.index) + JSON.stringify(item.doc) + '\n').join('');
  }

  private send(body: string, items: QueueEntry[], attempt = 0): void {
    const buf  = Buffer.from(body, 'utf8');
    const opts: https.RequestOptions = {
      hostname: this.parsed.hostname,
      port:     this.parsed.port,
      path:     this.parsed.pathname,
      method:   'POST',
      headers: {
        'Content-Type':   'application/x-ndjson',
        Accept:           'application/json',
        'Content-Length': buf.length,
        ...this.headers,
      },
      rejectUnauthorized: !this.trustAllSsl,
      timeout: this.timeoutMs,
    };
    if (this.auth) opts.headers!['Authorization'] = `Basic ${this.auth}`;
    const req = this.lib.request(opts, (res) => {
      let responseBody = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { responseBody += chunk; });
      res.on('end', () => {
        if (res.statusCode === 429 || (res.statusCode ?? 0) >= 500) {
          this.retryOrRequeue(items, `HTTP ${res.statusCode}: ${responseBody}`, attempt);
          return;
        }
        if ((res.statusCode ?? 0) >= 400) {
          console.warn(`[OpenSearch] 전송 실패: HTTP ${res.statusCode}: ${responseBody}`);
          return;
        }
        const result = this.analyzeBulkResponse(items, responseBody);
        if (result.success) return;
        if (result.retryable.length > 0) {
          this.retryOrRequeue(result.retryable, result.message, attempt);
          return;
        }
        console.warn(`[OpenSearch] 전송 실패: ${result.message}`);
      });
    });
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', (err: Error) => {
      this.retryOrRequeue(items, err.message, attempt);
    });
    req.write(buf);
    req.end();
  }

  private retryOrRequeue(items: QueueEntry[], message: string, attempt: number): void {
    if (attempt < this.maxRetries) {
      setTimeout(() => this.send(this.buildPayload(items), items, attempt + 1), 100);
      return;
    }
    if (this.queue.length + items.length <= this.maxQueue) {
      this.queue.unshift(...items);
      console.warn(`[OpenSearch] 전송 실패, ${items.length}건 재큐: ${message}`);
      if (!this.persistentWriterThread) this.startWriter();
    } else {
      console.error(`[OpenSearch] 전송 실패, ${items.length}건 유실 (큐 포화): ${message}`);
    }
  }

  private analyzeBulkResponse(items: QueueEntry[], body: string): { success: boolean; retryable: QueueEntry[]; message: string } {
    if (!body) return { success: true, retryable: [], message: '' };
    let root: any;
    try {
      root = JSON.parse(body);
    } catch {
      return { success: true, retryable: [], message: '' };
    }
    if (!root.errors) return { success: true, retryable: [], message: '' };

    const retryable: QueueEntry[] = [];
    const fatal: string[] = [];
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
