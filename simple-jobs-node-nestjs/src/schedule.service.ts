import { Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { randomUUID } from 'crypto';
import { OpenSearchJobAppender } from './opensearch.job-appender';

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
    });
  }

  onModuleInit() {
    const systemDelay   = parseInt(process.env.JOB_SYSTEM_DELAY   ?? '3000');
    const managerDelay  = parseInt(process.env.JOB_MANAGER_DELAY  ?? '15000');
    const operatorDelay = parseInt(process.env.JOB_OPERATOR_DELAY ?? '20000');

    this.register('system-job',   systemDelay,   () => this.doSystemJob());
    this.register('manager-job',  managerDelay,  () => this.doManagerJob());
    this.register('operator-job', operatorDelay, () => this.doOperatorJob());
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
}
