require('dotenv').config();
const express               = require('express');
const { randomUUID }        = require('crypto');
const OpenSearchJobAppender = require('./opensearch-job-appender');

const app          = express();
const PORT         = process.env.PORT ?? 3002;
const SYSTEM_DELAY  = parseInt(process.env.JOB_SYSTEM_DELAY   ?? '3000');
const MANAGER_DELAY = parseInt(process.env.JOB_MANAGER_DELAY  ?? '15000');
const OPERATOR_DELAY = parseInt(process.env.JOB_OPERATOR_DELAY ?? '20000');
const RISKY_DELAY = parseInt(process.env.JOB_RISKY_DELAY ?? '60000');

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
  url:                  process.env.OPENSEARCH_URL                 ?? 'https://localhost:9200',
  username:             process.env.OPENSEARCH_USERNAME            ?? '',
  password:             process.env.OPENSEARCH_PASSWORD            ?? '',
  app:                  process.env.OPENSEARCH_NAME                ?? 'simple-jobs-node-express',
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

function withRetry(fn, maxAttempts = 3) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      fn();
      return;
    } catch (e) {
      if (attempt === maxAttempts) {
        console.error(`[RECOVER] job failed after ${maxAttempts} retries: ${e.message}`);
      }
    }
  }
}

function riskyLogDelay() {
  return 3000 + Math.floor(Math.random() * 7001);
}

setInterval(() => withRetry(() => {
  const msg = `OS : Just do system job by node express [${randomUUID()}]`;
  console.log(msg);
  appender.log('INFO', msg, { job: 'system-job' });
}), SYSTEM_DELAY);

setInterval(() => withRetry(() => {
  const msg = `OS : Just do manager job by node express [${randomUUID()}]`;
  console.log(msg);
  appender.log('INFO', msg, { job: 'manager-job' });
}), MANAGER_DELAY);

setInterval(() => withRetry(() => {
  const msg = `OS : Just do operator job by node express [${randomUUID()}]`;
  console.log(msg);
  appender.log('INFO', msg, { job: 'operator-job' });
}), OPERATOR_DELAY);

setInterval(() => {
  const runId = randomUUID();
  if (Math.random() < 0.8) {
    const msg = `OS : Risky job completed normally by node express [${runId}]`;
    console.log(msg);
    appender.log('INFO', msg, { job: 'risky-job' });
    return;
  }

  setTimeout(() => {
    const level = Math.random() < 0.5 ? 'WARN' : 'ERROR';
    const msg = `OS : Risky job found unstable condition by node express [${runId}]`;
    console[level === 'WARN' ? 'warn' : 'error'](msg);
    appender.log(level, msg, { job: 'risky-job' });
  }, riskyLogDelay());
}, RISKY_DELAY);

process.on('SIGTERM', () => { appender.stop(); process.exit(0); });
process.on('SIGINT',  () => { appender.stop(); process.exit(0); });

app.listen(PORT, () => console.log(`simple-jobs-node-express running on port ${PORT}`));
