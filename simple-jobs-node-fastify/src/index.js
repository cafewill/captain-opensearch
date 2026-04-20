require('dotenv').config();
const Fastify               = require('fastify');
const { randomUUID }        = require('crypto');
const OpenSearchJobAppender = require('./opensearch-job-appender');

const app          = Fastify({ logger: false });
const PORT         = parseInt(process.env.PORT ?? '3003');
const SYSTEM_DELAY  = parseInt(process.env.JOB_SYSTEM_DELAY   ?? '3000');
const MANAGER_DELAY = parseInt(process.env.JOB_MANAGER_DELAY  ?? '15000');
const OPERATOR_DELAY = parseInt(process.env.JOB_OPERATOR_DELAY ?? '20000');

const appender = new OpenSearchJobAppender({
  url:                  process.env.OPENSEARCH_URL                 ?? 'https://localhost:9200',
  username:             process.env.OPENSEARCH_USERNAME            ?? '',
  password:             process.env.OPENSEARCH_PASSWORD            ?? '',
  app:                  process.env.OPENSEARCH_NAME                ?? 'simple-jobs-node-fastify',
  env:                  process.env.OPENSEARCH_ENV                 ?? 'local',
  maxBatchBytes:        parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      ?? '1000000'),
  flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1'),
  queueSize:            parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     ?? '8192'),
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

setInterval(() => withRetry(() => {
  const msg = `OS : Just do system job by node fastify [${randomUUID()}]`;
  console.log(msg);
  appender.log('INFO', msg, { job: 'system-job' });
}), SYSTEM_DELAY);

setInterval(() => withRetry(() => {
  const msg = `OS : Just do manager job by node fastify [${randomUUID()}]`;
  console.log(msg);
  appender.log('INFO', msg, { job: 'manager-job' });
}), MANAGER_DELAY);

setInterval(() => withRetry(() => {
  const msg = `OS : Just do operator job by node fastify [${randomUUID()}]`;
  console.log(msg);
  appender.log('INFO', msg, { job: 'operator-job' });
}), OPERATOR_DELAY);

process.on('SIGTERM', () => { appender.stop(); process.exit(0); });
process.on('SIGINT',  () => { appender.stop(); process.exit(0); });

app.listen({ port: PORT, host: '0.0.0.0' }, (err) => {
  if (err) { console.error(err); process.exit(1); }
  console.log(`simple-jobs-node-fastify running on port ${PORT}`);
});
