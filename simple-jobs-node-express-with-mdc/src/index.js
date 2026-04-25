require('dotenv').config();
const express = require('express');
const { randomUUID } = require('crypto');
const OpenSearchJobAppender = require('./opensearch-job-appender');

const app = express();
const PORT = parseInt(process.env.PORT ?? '3012');
const FRAMEWORK = 'node-express-with-mdc';
const APP_NAME = process.env.OPENSEARCH_NAME ?? 'simple-jobs-node-express-with-mdc';

const SYSTEM_DELAY = parseInt(process.env.JOB_SYSTEM_DELAY ?? '3000');
const MANAGER_DELAY = parseInt(process.env.JOB_MANAGER_DELAY ?? '15000');
const OPERATOR_DELAY = parseInt(process.env.JOB_OPERATOR_DELAY ?? '20000');
const RISKY_DELAY = parseInt(process.env.JOB_RISKY_DELAY ?? '60000');
const SLOW_DELAY = parseInt(process.env.JOB_SLOW_DELAY ?? '60000');

const P95_THRESHOLD_MS = parseInt(process.env.API_MONITORING_P95_THRESHOLD_MS ?? '800');
const P99_THRESHOLD_MS = parseInt(process.env.API_MONITORING_P99_THRESHOLD_MS ?? '1500');
const SLOW_QUERY_THRESHOLD_MS = parseInt(process.env.API_MONITORING_SLOW_QUERY_THRESHOLD_MS ?? '1200');

function envBool(name, defaultValue) {
  return ['1', 'true', 'yes', 'y'].includes(String(process.env[name] ?? defaultValue).toLowerCase());
}

function envHeaders() {
  try {
    return JSON.parse(process.env.OPENSEARCH_HEADERS ?? '{}');
  } catch {
    return {};
  }
}

const appender = new OpenSearchJobAppender({
  url: process.env.OPENSEARCH_URL ?? 'https://localhost:9200',
  username: process.env.OPENSEARCH_USERNAME ?? '',
  password: process.env.OPENSEARCH_PASSWORD ?? '',
  app: APP_NAME,
  env: process.env.OPENSEARCH_ENV ?? 'local',
  maxBatchBytes: parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES ?? '1000000'),
  flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1'),
  queueSize: parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE ?? '8192'),
  operation: process.env.OPENSEARCH_BULK_OPERATION ?? 'create',
  trustAllSsl: envBool('OPENSEARCH_TRUST_ALL_SSL', true),
  timeout: parseInt(process.env.OPENSEARCH_TIMEOUT ?? '10'),
  maxRetries: parseInt(process.env.OPENSEARCH_MAX_RETRIES ?? '3'),
  headers: envHeaders(),
  persistentWriterThread: envBool('OPENSEARCH_PERSISTENT_WRITER_THREAD', true),
});

const jobProfiles = {
  system: ['platform-maintenance', 'cron', 'worker-a1', '00:00-00:05', 'system-checkpoint'],
  manager: ['control-plane', 'fixed-delay', 'worker-b2', '00:05-00:20', 'manager-checkpoint'],
  operator: ['runtime-ops', 'fixed-delay', 'worker-c3', '00:20-00:40', 'operator-checkpoint'],
  risky: ['risk-control', 'fixed-rate', 'worker-r9', '00:40-01:00', 'risky-checkpoint'],
  slow: ['performance-watch', 'fixed-rate', 'worker-p5', '01:00-01:05', 'slow-method-checkpoint'],
};

function batchContext(jobName, runId, status, elapsedMs) {
  const [jobGroup, triggerType, workerNode, batchWindow, checkpointName] = jobProfiles[jobName] ?? jobProfiles.system;
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

function apiPerformanceContext(scenarioName, runId, responseTime) {
  const slowQuery = responseTime >= SLOW_QUERY_THRESHOLD_MS;
  const slowMethod = responseTime >= 1000;
  const percentileTarget = responseTime >= P99_THRESHOLD_MS ? 'p99' : responseTime >= P95_THRESHOLD_MS ? 'p95' : 'normal';
  const latencyBucket = responseTime < 200 ? '000-199ms'
    : responseTime < 500 ? '200-499ms'
    : responseTime < P95_THRESHOLD_MS ? '500-799ms'
    : responseTime < P99_THRESHOLD_MS ? '800-1499ms'
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
    p95ThresholdMs: String(P95_THRESHOLD_MS),
    p99ThresholdMs: String(P99_THRESHOLD_MS),
    slowQueryThresholdMs: String(SLOW_QUERY_THRESHOLD_MS),
    slowQueryAlarm: String(slowQuery),
    slowQueryAlarmRule: `responseTime >= ${SLOW_QUERY_THRESHOLD_MS}ms`,
    slowMethodAlarm: String(slowMethod),
    slowMethodThresholdMs: '1000',
    slowMethodAlarmRule: 'responseTime >= 1000ms',
    dbPoolName: 'items-reader',
    dashboardPanel: 'api-latency-p95-p99-heatmap',
  };
}

function logJob(jobName, role) {
  const runId = randomUUID();
  const fields = {
    traceId: runId,
    framework: FRAMEWORK,
    appVariant: 'with-mdc',
    mdcSample: 'enabled',
    success: 'true',
    ...batchContext(jobName, runId, 'success', 1500),
  };
  const msg = `OS+MDC : Batch scheduler job ${fields.jobExecutionId} tracked by node express with MDC [${runId}]`;
  console.log(msg);
  appender.log('INFO', msg, fields);
}

function riskyJob() {
  const runId = randomUUID();
  let level = 'INFO';
  let success = true;
  const emit = () => {
    const responseTime = level === 'ERROR'
      ? randomInt(P99_THRESHOLD_MS, 3200)
      : level === 'WARN'
        ? randomInt(P95_THRESHOLD_MS, P99_THRESHOLD_MS - 1)
        : randomInt(80, P95_THRESHOLD_MS - 1);
    const fields = {
      traceId: runId,
      jobName: 'risky',
      jobRole: 'risk-control',
      framework: FRAMEWORK,
      appVariant: 'with-mdc',
      mdcSample: 'enabled',
      riskOutcome: level.toLowerCase(),
      success: String(success),
      ...batchContext('risky', runId, success ? 'success' : 'degraded', 7310),
      ...apiPerformanceContext('risky-search', runId, responseTime),
    };
    const msg = `OS+MDC : Risky job produced ${level} API latency sample responseTime=${responseTime}ms percentile=${fields.percentileTarget} slowQueryAlarm=${fields.slowQueryAlarm} by node express with MDC [${runId}]`;
    console[level === 'ERROR' ? 'error' : level === 'WARN' ? 'warn' : 'log'](msg);
    appender.log(level, msg, fields);
  };
  if (Math.random() < 0.8) {
    emit();
    return;
  }
  level = Math.random() < 0.5 ? 'WARN' : 'ERROR';
  success = false;
  setTimeout(emit, randomInt(3000, 10000));
}

function slowJob() {
  const runId = randomUUID();
  const responseTime = Math.random() < 0.9 ? randomInt(20, 100) : randomInt(1000, 5000);
  setTimeout(() => {
    const slowMethod = responseTime >= 1000;
    const level = slowMethod ? 'WARN' : 'INFO';
    const fields = {
      traceId: runId,
      jobName: 'slow',
      jobRole: 'performance-watch',
      framework: FRAMEWORK,
      appVariant: 'with-mdc',
      mdcSample: 'enabled',
      success: String(!slowMethod),
      ...batchContext('slow', runId, slowMethod ? 'slow' : 'success', responseTime),
      ...apiPerformanceContext('slow-method', runId, responseTime),
    };
    const msg = `OS+MDC : Slow job method responseTime=${responseTime}ms slowMethodAlarm=${fields.slowMethodAlarm} by node express with MDC [${runId}]`;
    console[slowMethod ? 'warn' : 'log'](msg);
    appender.log(level, msg, fields);
  }, responseTime >= 1000 ? responseTime : 0);
}

function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

setInterval(() => logJob('system', 'platform'), SYSTEM_DELAY);
setInterval(() => logJob('manager', 'control'), MANAGER_DELAY);
setInterval(() => logJob('operator', 'runtime'), OPERATOR_DELAY);
setInterval(riskyJob, RISKY_DELAY);
setInterval(slowJob, SLOW_DELAY);

process.on('SIGTERM', () => { appender.stop(); process.exit(0); });
process.on('SIGINT', () => { appender.stop(); process.exit(0); });

app.listen(PORT, () => console.log(`simple-jobs-node-express-with-mdc running on port ${PORT}`));
