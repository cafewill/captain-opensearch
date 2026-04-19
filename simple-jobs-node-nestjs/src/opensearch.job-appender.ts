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

/**
 * OpenSearch _bulk API 어펜더 — Node.js 내장 모듈만 사용 (추가 의존성 없음)
 *
 * 다른 프로젝트 반영 시 이 파일 하나만 복사
 *   1. src/ 에 복사 (패키지명 불필요, 클래스 그대로 사용)
 *   2. .env 에 OPENSEARCH_* 환경변수 추가
 *   3. import { OpenSearchJobAppender } from './opensearch.job-appender' 후 인스턴스 생성
 */

export interface OpenSearchJobAppenderConfig {
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
  private readonly auth:       string | null;
  private readonly lib:        typeof https | typeof http;
  private readonly parsed:     URL;
  private readonly queue:      QueueEntry[] = [];
  private readonly timer:      NodeJS.Timeout;

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
  }: OpenSearchJobAppenderConfig = {}) {
    this.index      = `logs-${app}`;
    this.app        = app;
    this.env        = env;
    this.instanceId = process.env.HOSTNAME || os.hostname();
    this.maxBytes   = maxBatchBytes;
    this.maxQueue   = queueSize;
    this.auth       = username
      ? Buffer.from(`${username}:${password}`).toString('base64')
      : null;
    this.lib    = scheme === 'https' ? https : http;
    this.parsed = new URL(`${scheme}://${host}:${port}/_bulk`);
    this.timer  = setInterval(() => this.flush(), flushIntervalSeconds * 1000);
    this.timer.unref();
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
  }

  stop(): void {
    clearInterval(this.timer);
    this.flush();
  }

  private flush(): void {
    if (this.queue.length === 0) return;
    const items = this.queue.splice(0);
    let bulk = '';
    let batchItems: QueueEntry[] = [];
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

  private send(body: string, items: QueueEntry[]): void {
    const buf  = Buffer.from(body, 'utf8');
    const opts: https.RequestOptions = {
      hostname: this.parsed.hostname,
      port:     this.parsed.port,
      path:     this.parsed.pathname,
      method:   'POST',
      headers: {
        'Content-Type':   'application/json; charset=UTF-8',
        'Content-Length': buf.length,
      },
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
