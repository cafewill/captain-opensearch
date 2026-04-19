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

export interface OpenSearchWebAppenderConfig {
  scheme?:               string;
  host?:                 string;
  port?:                 number;
  username?:             string;
  password?:             string;
  app?:                  string;
  env?:                  string;
  maxBatchBytes?:        number;
  flushIntervalSeconds?: number;
  queueSize?:            number;
}

@Injectable()
export class OpenSearchWebAppender implements NestMiddleware {
  private readonly index:      string;
  private readonly app:        string;
  private readonly env:        string;
  private readonly instanceId: string;
  private readonly maxBytes:   number;
  private readonly maxQueue:   number;
  private readonly auth:       string | null;
  private readonly lib:        typeof https | typeof http;
  private readonly parsed:     URL;
  private readonly queue:      Array<{ index: string; doc: Record<string, unknown> }> = [];
  private readonly timer:      NodeJS.Timeout;

  constructor() {
    const scheme              = process.env.OPENSEARCH_SCHEME              ?? 'https';
    const host                = process.env.OPENSEARCH_HOST                ?? 'localhost';
    const port                = parseInt(process.env.OPENSEARCH_PORT       ?? '9200');
    const username            = process.env.OPENSEARCH_USERNAME            ?? '';
    const password            = process.env.OPENSEARCH_PASSWORD            ?? '';
    const app                 = process.env.OPENSEARCH_NAME                ?? 'simple-rest-node-nestjs';
    const env                 = process.env.OPENSEARCH_ENV                 ?? 'local';
    const maxBatchBytes       = parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      ?? '1000000');
    const flushIntervalSeconds = parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1');
    const queueSize           = parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     ?? '8192');

    this.index      = `logs-${app}`;
    this.app        = app;
    this.env        = env;
    this.instanceId = process.env.HOSTNAME || os.hostname();
    this.maxBytes   = maxBatchBytes;
    this.maxQueue   = queueSize;
    this.auth       = username ? Buffer.from(`${username}:${password}`).toString('base64') : null;
    this.lib        = scheme === 'https' ? https : http;
    this.parsed     = new URL(`${scheme}://${host}:${port}/_bulk`);
    this.timer      = setInterval(() => this.flush(), flushIntervalSeconds * 1000);
    this.timer.unref();
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
  }

  stop(): void { clearInterval(this.timer); this.flush(); }

  private flush(): void {
    if (this.queue.length === 0) return;
    const items = this.queue.splice(0);
    let bulk = '';
    let batchItems: Array<{ index: string; doc: Record<string, unknown> }> = [];
    for (const item of items) {
      const meta    = JSON.stringify({ index: { _index: item.index } }) + '\n';
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
  }

  private send(body: string, items: Array<{ index: string; doc: Record<string, unknown> }>): void {
    const buf = Buffer.from(body, 'utf8');
    const opts: https.RequestOptions = {
      hostname: this.parsed.hostname, port: this.parsed.port, path: this.parsed.pathname,
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=UTF-8', 'Content-Length': buf.length },
      rejectUnauthorized: false,
    };
    if (this.auth) opts.headers!['Authorization'] = `Basic ${this.auth}`;
    const req = this.lib.request(opts, (res) => { res.resume(); });
    req.on('error', (err: Error) => {
      if (this.queue.length + items.length <= this.maxQueue) {
        this.queue.unshift(...items);
        console.warn(`[OpenSearch] 전송 실패, ${items.length}건 재큐: ${err.message}`);
      } else {
        console.error(`[OpenSearch] 전송 실패, ${items.length}건 유실 (큐 포화): ${err.message}`);
      }
    });
    req.write(buf);
    req.end();
  }
}
