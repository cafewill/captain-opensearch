import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { randomUUID } from 'crypto';
import { OpenSearchJobAppender } from './opensearch.job-appender';

type ExtraFields = Record<string, string>;

@Injectable()
export class ScheduleService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(ScheduleService.name);
  private readonly appender: OpenSearchJobAppender;
  private readonly framework = 'node-nestjs-with-mdc';
  private readonly p95ThresholdMs = parseInt(process.env.API_MONITORING_P95_THRESHOLD_MS ?? '800');
  private readonly p99ThresholdMs = parseInt(process.env.API_MONITORING_P99_THRESHOLD_MS ?? '1500');
  private readonly slowQueryThresholdMs = parseInt(process.env.API_MONITORING_SLOW_QUERY_THRESHOLD_MS ?? '1200');

  constructor(private readonly schedulerRegistry: SchedulerRegistry) {
    this.appender = new OpenSearchJobAppender({
      url: process.env.OPENSEARCH_URL ?? 'https://localhost:9200',
      username: process.env.OPENSEARCH_USERNAME ?? '',
      password: process.env.OPENSEARCH_PASSWORD ?? '',
      app: process.env.OPENSEARCH_NAME ?? 'simple-jobs-node-nestjs-with-mdc',
      env: process.env.OPENSEARCH_ENV ?? 'local',
      maxBatchBytes: parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES ?? '1000000'),
      flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1'),
      queueSize: parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE ?? '8192'),
    });
  }

  onModuleInit(): void {
    this.register('system-job', parseInt(process.env.JOB_SYSTEM_DELAY ?? '3000'), () => this.logJob('system'));
    this.register('manager-job', parseInt(process.env.JOB_MANAGER_DELAY ?? '15000'), () => this.logJob('manager'));
    this.register('operator-job', parseInt(process.env.JOB_OPERATOR_DELAY ?? '20000'), () => this.logJob('operator'));
    this.register('risky-job', parseInt(process.env.JOB_RISKY_DELAY ?? '60000'), () => this.riskyJob());
    this.register('slow-job', parseInt(process.env.JOB_SLOW_DELAY ?? '60000'), () => this.slowJob());
  }

  onModuleDestroy(): void {
    this.appender.stop();
  }

  private register(name: string, delay: number, fn: () => void): void {
    const interval = setInterval(fn, delay);
    this.schedulerRegistry.addInterval(name, interval);
  }

  private logJob(jobName: string): void {
    const runId = randomUUID();
    const fields: ExtraFields = {
      traceId: runId,
      framework: this.framework,
      appVariant: 'with-mdc',
      mdcSample: 'enabled',
      success: 'true',
      ...this.batchContext(jobName, runId, 'success', 1500),
    };
    const msg = `OS+MDC : Batch scheduler job ${fields.jobExecutionId} tracked by node nestjs with MDC [${runId}]`;
    this.logger.log(msg);
    this.appender.log('INFO', msg, fields);
  }

  private riskyJob(): void {
    const runId = randomUUID();
    let level = 'INFO';
    let success = true;
    const emit = () => {
      const responseTime = level === 'ERROR'
        ? this.randomInt(this.p99ThresholdMs, 3200)
        : level === 'WARN'
          ? this.randomInt(this.p95ThresholdMs, this.p99ThresholdMs - 1)
          : this.randomInt(80, this.p95ThresholdMs - 1);
      const fields: ExtraFields = {
        traceId: runId,
        jobName: 'risky',
        jobRole: 'risk-control',
        framework: this.framework,
        appVariant: 'with-mdc',
        mdcSample: 'enabled',
        riskOutcome: level.toLowerCase(),
        success: String(success),
        ...this.batchContext('risky', runId, success ? 'success' : 'degraded', 7310),
        ...this.apiPerformanceContext('risky-search', runId, responseTime),
      };
      const msg = `OS+MDC : Risky job produced ${level} API latency sample responseTime=${responseTime}ms percentile=${fields.percentileTarget} slowQueryAlarm=${fields.slowQueryAlarm} by node nestjs with MDC [${runId}]`;
      if (level === 'ERROR') {
        this.logger.error(msg);
      } else if (level === 'WARN') {
        this.logger.warn(msg);
      } else {
        this.logger.log(msg);
      }
      this.appender.log(level, msg, fields);
    };
    if (Math.random() < 0.8) {
      emit();
      return;
    }
    level = Math.random() < 0.5 ? 'WARN' : 'ERROR';
    success = false;
    setTimeout(emit, this.randomInt(3000, 10000));
  }

  private slowJob(): void {
    const runId = randomUUID();
    const responseTime = Math.random() < 0.9 ? this.randomInt(20, 100) : this.randomInt(1000, 5000);
    setTimeout(() => {
      const slowMethod = responseTime >= 1000;
      const level = slowMethod ? 'WARN' : 'INFO';
      const fields: ExtraFields = {
        traceId: runId,
        jobName: 'slow',
        jobRole: 'performance-watch',
        framework: this.framework,
        appVariant: 'with-mdc',
        mdcSample: 'enabled',
        success: String(!slowMethod),
        ...this.batchContext('slow', runId, slowMethod ? 'slow' : 'success', responseTime),
        ...this.apiPerformanceContext('slow-method', runId, responseTime),
      };
      const msg = `OS+MDC : Slow job method responseTime=${responseTime}ms slowMethodAlarm=${fields.slowMethodAlarm} by node nestjs with MDC [${runId}]`;
      if (slowMethod) {
        this.logger.warn(msg);
      } else {
        this.logger.log(msg);
      }
      this.appender.log(level, msg, fields);
    }, responseTime >= 1000 ? responseTime : 0);
  }

  private batchContext(jobName: string, runId: string, status: string, elapsedMs: number): ExtraFields {
    const profiles: Record<string, string[]> = {
      system: ['platform-maintenance', 'cron', 'worker-a1', '00:00-00:05', 'system-checkpoint'],
      manager: ['control-plane', 'fixed-delay', 'worker-b2', '00:05-00:20', 'manager-checkpoint'],
      operator: ['runtime-ops', 'fixed-delay', 'worker-c3', '00:20-00:40', 'operator-checkpoint'],
      risky: ['risk-control', 'fixed-rate', 'worker-r9', '00:40-01:00', 'risky-checkpoint'],
      slow: ['performance-watch', 'fixed-rate', 'worker-p5', '01:00-01:05', 'slow-method-checkpoint'],
    };
    const [jobGroup, triggerType, workerNode, batchWindow, checkpointName] = profiles[jobName] ?? profiles.system;
    return {
      observabilityUseCase: 'batch-scheduler-tracking',
      schedulerName: 'node-scheduler',
      jobName,
      jobGroup,
      jobRole: jobGroup,
      jobExecutionId: `job-${runId.slice(0, 8)}`,
      triggerType,
      workerNode,
      batchWindow,
      checkpointName,
      scheduledAt: new Date().toISOString(),
      runStatus: status,
      retryAttempt: '0',
      elapsed_ms: String(elapsedMs),
    };
  }

  private apiPerformanceContext(scenarioName: string, runId: string, responseTime: number): ExtraFields {
    const slowQuery = responseTime >= this.slowQueryThresholdMs;
    const slowMethod = responseTime >= 1000;
    const percentileTarget = responseTime >= this.p99ThresholdMs
      ? 'p99'
      : responseTime >= this.p95ThresholdMs ? 'p95' : 'normal';
    const latencyBucket = responseTime < 200 ? '000-199ms'
      : responseTime < 500 ? '200-499ms'
      : responseTime < this.p95ThresholdMs ? '500-799ms'
      : responseTime < this.p99ThresholdMs ? '800-1499ms'
      : '1500ms-plus';
    return {
      observabilityUseCase: 'api-performance-monitoring',
      traceId: runId,
      apiScenario: scenarioName,
      httpMethod: 'GET',
      apiRoute: '/api/items/search',
      queryName: 'items-search-by-status',
      responseTime: String(responseTime),
      responseTimeMs: String(responseTime),
      latencyBucket,
      latencyHeatmapCell: `${percentileTarget}:${latencyBucket}`,
      percentileTarget,
      p95ThresholdMs: String(this.p95ThresholdMs),
      p99ThresholdMs: String(this.p99ThresholdMs),
      slowQueryThresholdMs: String(this.slowQueryThresholdMs),
      slowQueryAlarm: String(slowQuery),
      slowQueryAlarmRule: `responseTime >= ${this.slowQueryThresholdMs}ms`,
      slowMethodAlarm: String(slowMethod),
      slowMethodThresholdMs: '1000',
      slowMethodAlarmRule: 'responseTime >= 1000ms',
      dbPoolName: 'items-reader',
      dashboardPanel: 'api-latency-p95-p99-heatmap',
    };
  }

  private randomInt(min: number, max: number): number {
    return min + Math.floor(Math.random() * (max - min + 1));
  }
}
