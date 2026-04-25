import { Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { randomUUID } from 'crypto';
import { OpenSearchJobAppender } from './opensearch.job-appender';

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

@Injectable()
export class ScheduleService implements OnModuleInit, OnModuleDestroy {
  private readonly logger   = new Logger(ScheduleService.name);
  private readonly appender: OpenSearchJobAppender;

  constructor(private readonly schedulerRegistry: SchedulerRegistry) {
    this.appender = new OpenSearchJobAppender({
      url:                  process.env.OPENSEARCH_URL                 ?? 'https://localhost:9200',
      username:             process.env.OPENSEARCH_USERNAME            ?? '',
      password:             process.env.OPENSEARCH_PASSWORD            ?? '',
      app:                  process.env.OPENSEARCH_NAME                ?? 'simple-jobs-node-nestjs',
      env:                  process.env.OPENSEARCH_ENV                 ?? 'local',
      maxBatchBytes:        parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      ?? '1000000'),
      flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1'),
      queueSize:            parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     ?? '8192'),
      operation:            process.env.OPENSEARCH_BULK_OPERATION ?? 'create',
      trustAllSsl:          envBool('OPENSEARCH_TRUST_ALL_SSL', true),
      timeout:              parseInt(process.env.OPENSEARCH_TIMEOUT ?? '10'),
      maxRetries:           parseInt(process.env.OPENSEARCH_MAX_RETRIES ?? '3'),
      headers:              envHeaders(),
      persistentWriterThread: envBool('OPENSEARCH_PERSISTENT_WRITER_THREAD', true),
    });
  }

  onModuleInit() {
    const systemDelay   = parseInt(process.env.JOB_SYSTEM_DELAY   ?? '3000');
    const managerDelay  = parseInt(process.env.JOB_MANAGER_DELAY  ?? '15000');
    const operatorDelay = parseInt(process.env.JOB_OPERATOR_DELAY ?? '20000');
    const riskyDelay    = parseInt(process.env.JOB_RISKY_DELAY    ?? '60000');

    this.register('system-job',   systemDelay,   () => this.doSystemJob());
    this.register('manager-job',  managerDelay,  () => this.doManagerJob());
    this.register('operator-job', operatorDelay, () => this.doOperatorJob());
    this.register('risky-job',    riskyDelay,    () => this.doRiskyJob());
  }

  onModuleDestroy() {
    this.appender.stop();
  }

  private register(name: string, delay: number, fn: () => void) {
    const interval = setInterval(fn, delay);
    this.schedulerRegistry.addInterval(name, interval);
  }

  private withRetry(fn: () => void, maxAttempts = 3): void {
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        fn();
        return;
      } catch (e) {
        if (attempt === maxAttempts) {
          this.logger.error(`job failed after ${maxAttempts} retries: ${(e as Error).message}`);
        }
      }
    }
  }

  private doSystemJob() {
    this.withRetry(() => {
      const msg = `OS : Just do system job by node nestjs [${randomUUID()}]`;
      this.logger.log(msg);
      this.appender.log('INFO', msg, { job: 'system-job' });
    });
  }

  private doManagerJob() {
    this.withRetry(() => {
      const msg = `OS : Just do manager job by node nestjs [${randomUUID()}]`;
      this.logger.log(msg);
      this.appender.log('INFO', msg, { job: 'manager-job' });
    });
  }

  private doOperatorJob() {
    this.withRetry(() => {
      const msg = `OS : Just do operator job by node nestjs [${randomUUID()}]`;
      this.logger.log(msg);
      this.appender.log('INFO', msg, { job: 'operator-job' });
    });
  }

  private doRiskyJob() {
    const runId = randomUUID();
    if (Math.random() < 0.8) {
      const msg = `OS : Risky job completed normally by node nestjs [${runId}]`;
      this.logger.log(msg);
      this.appender.log('INFO', msg, { job: 'risky-job' });
      return;
    }

    setTimeout(() => {
      const level = Math.random() < 0.5 ? 'WARN' : 'ERROR';
      const msg = `OS : Risky job found unstable condition by node nestjs [${runId}]`;
      if (level === 'WARN') {
        this.logger.warn(msg);
      } else {
        this.logger.error(msg);
      }
      this.appender.log(level, msg, { job: 'risky-job' });
    }, 3000 + Math.floor(Math.random() * 7001));
  }
}
