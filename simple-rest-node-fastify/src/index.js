'use strict';
require('dotenv').config();
const Fastify            = require('fastify');
const { randomUUID }     = require('crypto');
const OpenSearchWebAppender = require('./opensearch-web-appender');
const itemsRoutes        = require('./routes/items');

const PORT     = parseInt(process.env.PORT ?? '3203');

const appender = new OpenSearchWebAppender({
  scheme:               process.env.OPENSEARCH_SCHEME              ?? 'https',
  host:                 process.env.OPENSEARCH_HOST                ?? 'localhost',
  port:                 parseInt(process.env.OPENSEARCH_PORT       ?? '9200'),
  username:             process.env.OPENSEARCH_USERNAME            ?? '',
  password:             process.env.OPENSEARCH_PASSWORD            ?? '',
  app:                  process.env.OPENSEARCH_NAME                ?? 'simple-rest-node-fastify',
  env:                  process.env.OPENSEARCH_ENV                 ?? 'local',
  maxBatchBytes:        parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      ?? '1000000'),
  flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1'),
  queueSize:            parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     ?? '8192'),
});

const app = Fastify({ logger: false });

// OpenSearch 요청 추적 훅
app.addHook('onRequest', async (request) => {
  const traceId = request.headers['x-request-id'] || randomUUID();
  request.traceId   = traceId;
  request.startTime = Date.now();
  request.log = {
    info: (msg, extra = {}) => {
      const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
      console.log(`[${ts}] INFO  ${msg}`);
      appender.log('INFO',  msg, { trace_id: traceId, ...extra });
    },
    error: (msg, extra = {}) => {
      const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
      console.error(`[${ts}] ERROR ${msg}`);
      appender.log('ERROR', msg, { trace_id: traceId, ...extra });
    },
  };
});

app.addHook('onSend', async (request, reply) => {
  const duration = Date.now() - (request.startTime || Date.now());
  const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
  console.log(`[${ts}] ${request.method} ${request.url} → ${reply.statusCode} (${duration}ms) [${request.traceId}]`);
  appender.log('INFO', `${request.method} ${request.url} → ${reply.statusCode}`, {
    trace_id:    request.traceId,
    http_method: request.method,
    http_path:   request.routerPath || request.url,
    client_ip:   (request.headers['x-forwarded-for'] || request.ip || '').split(',')[0].trim(),
    http_status: reply.statusCode,
    duration_ms: duration,
  });
  reply.header('X-Request-ID', request.traceId);
});

app.register(itemsRoutes, { prefix: '/api/items' });
app.get('/health', async () => ({ status: 'ok' }));

process.on('SIGTERM', () => { appender.stop(); process.exit(0); });
process.on('SIGINT',  () => { appender.stop(); process.exit(0); });

app.listen({ port: PORT, host: '0.0.0.0' }, (err) => {
  if (err) { console.error(err); process.exit(1); }
  console.log(`simple-rest-node-fastify running on port ${PORT}`);
});
