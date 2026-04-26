import * as https from 'https';
import * as http  from 'http';
import * as os    from 'os';
import { randomUUID } from 'crypto';
import { Injectable, NestMiddleware } from '@nestjs/common';
import { Request, Response, NextFunction } from 'express';

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
  return value === 'index' || value === 'create' ? value : 'index';
}

function bulkUrl(url: string): string {
  const base = String(url || '').replace(/\/+$/, '');
  return base.endsWith('/_bulk') ? base : `${base}/_bulk`;
}

function envBool(name: string, defaultValue: boolean): boolean {
  return ['1', 'true', 'yes', 'y'].includes(String(process.env[name] ?? defaultValue).toLowerCase());
}

function envHeaders(): Record<string, string> {
  try {
    return JSON.parse(process.env.OPENSEARCH_HEADERS ?? '{}');
  } catch {
    return {};
  }
}

export interface OpenSearchWebAppenderConfig {
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
  requeueOnFailure?: boolean;
}

@Injectable()
export class OpenSearchWebAppender implements NestMiddleware {
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
  private readonly requeueOnFailure: boolean;
  private readonly auth:       string | null;
  private readonly lib:        typeof https | typeof http;
  private readonly parsed:     URL;
  private readonly queue:      Array<{ index: string; doc: Record<string, unknown> }> = [];
  private readonly flushIntervalMs: number;
  private timer:             NodeJS.Timeout | null = null;

  constructor() {
    const url                 = process.env.OPENSEARCH_URL                 ?? 'https://localhost:9200';
    const username            = process.env.OPENSEARCH_USERNAME            ?? '';
    const password            = process.env.OPENSEARCH_PASSWORD            ?? '';
    const app                 = process.env.OPENSEARCH_NAME                ?? 'simple-rest-node-nestjs';
    const env                 = process.env.OPENSEARCH_ENV                 ?? 'local';
    const maxBatchBytes       = parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      ?? '1000000');
    const flushIntervalSeconds = parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1');
    const queueSize           = parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     ?? '8192');
    const operation           = process.env.OPENSEARCH_BULK_OPERATION ?? 'index';
    const trustAllSsl         = envBool('OPENSEARCH_TRUST_ALL_SSL', true);
    const timeout             = parseInt(process.env.OPENSEARCH_TIMEOUT ?? '10');
    const maxRetries          = parseInt(process.env.OPENSEARCH_MAX_RETRIES ?? '3');
    const headers             = envHeaders();
    const persistentWriterThread = envBool('OPENSEARCH_PERSISTENT_WRITER_THREAD', true);
    const requeueOnFailure    = envBool('OPENSEARCH_REQUEUE_ON_FAILURE', true);

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
    this.requeueOnFailure = requeueOnFailure;
    this.auth       = username ? Buffer.from(`${username}:${password}`).toString('base64') : null;
    this.parsed     = new URL(bulkUrl(url));
    this.lib        = this.parsed.protocol === 'https:' ? https : http;
    this.flushIntervalMs = flushIntervalSeconds * 1000;
    if (this.persistentWriterThread) this.startWriter();
  }

  /** NestJS Middleware — 요청 추적 + access log 발행 */
  use(req: Request, res: Response, next: NextFunction): void {
    const traceId = (req.headers['x-request-id'] as string) || randomUUID();
    (req as any).traceId = traceId;
    res.setHeader('X-Request-ID', traceId);

    const start = Date.now();
    res.on('finish', () => {
      const duration = Date.now() - start;
      const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
      console.log(`[${ts}] ${req.method} ${req.path} → ${res.statusCode} (${duration}ms) [${traceId}]`);
      this.log('INFO', `${req.method} ${req.path} → ${res.statusCode}`, {
        trace_id:    traceId,
        http_method: req.method,
        http_path:   req.path,
        client_ip:   ((req.headers['x-forwarded-for'] as string) || req.socket.remoteAddress || '').split(',')[0].trim(),
        http_status: res.statusCode,
        duration_ms: duration,
      });
    });
    next();
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
        level, message, app: this.app, env: this.env, instance_id: this.instanceId, ...extra,
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
    let batchItems: Array<{ index: string; doc: Record<string, unknown> }> = [];
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

  private buildPayload(items: Array<{ index: string; doc: Record<string, unknown> }>): string {
    return items.map((item) => this.actionLine(item.index) + JSON.stringify(item.doc) + '\n').join('');
  }

  private send(body: string, items: Array<{ index: string; doc: Record<string, unknown> }>, attempt = 0): void {
    const buf = Buffer.from(body, 'utf8');
    const opts: https.RequestOptions = {
      hostname: this.parsed.hostname, port: this.parsed.port, path: this.parsed.pathname,
      method: 'POST',
      headers: { 'Content-Type': 'application/x-ndjson', Accept: 'application/json', 'Content-Length': buf.length, ...this.headers },
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

  private retryOrRequeue(items: Array<{ index: string; doc: Record<string, unknown> }>, message: string, attempt: number): void {
    if (attempt < this.maxRetries) {
      setTimeout(() => this.send(this.buildPayload(items), items, attempt + 1), 100);
      return;
    }
    if (!this.requeueOnFailure) {
      console.error(`[OpenSearch] 전송 실패, ${items.length}건 유실 (재큐 비활성): ${message}`);
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

  private analyzeBulkResponse(
    items: Array<{ index: string; doc: Record<string, unknown> }>,
    body: string,
  ): { success: boolean; retryable: Array<{ index: string; doc: Record<string, unknown> }>; message: string } {
    if (!body) return { success: true, retryable: [], message: '' };
    let root: any;
    try {
      root = JSON.parse(body);
    } catch {
      return { success: true, retryable: [], message: '' };
    }
    if (!root.errors) return { success: true, retryable: [], message: '' };

    const retryable: Array<{ index: string; doc: Record<string, unknown> }> = [];
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
