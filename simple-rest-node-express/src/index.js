'use strict';
require('dotenv').config();
const express            = require('express');
const OpenSearchWebAppender = require('./opensearch-web-appender');
const itemsRouter        = require('./routes/items');

const app      = express();
const PORT     = process.env.PORT ?? 3202;

const appender = new OpenSearchWebAppender({
  url:                  process.env.OPENSEARCH_URL                 ?? 'https://localhost:9200',
  username:             process.env.OPENSEARCH_USERNAME            ?? '',
  password:             process.env.OPENSEARCH_PASSWORD            ?? '',
  app:                  process.env.OPENSEARCH_NAME                ?? 'simple-rest-node-express',
  env:                  process.env.OPENSEARCH_ENV                 ?? 'local',
  maxBatchBytes:        parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      ?? '1000000'),
  flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL ?? '1'),
  queueSize:            parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     ?? '8192'),
});

app.use(express.json());
app.use(appender.middleware());

// req.log 헬퍼 — 라우터에서 OpenSearch 로그 전송용
app.use((req, _res, next) => {
  req.log = (level, message, extra = {}) =>
    appender.log(level, message, { trace_id: req.traceId, ...extra });
  next();
});

app.use('/api/items', itemsRouter);

app.get('/health', (_req, res) => res.json({ status: 'ok' }));

process.on('SIGTERM', () => { appender.stop(); process.exit(0); });
process.on('SIGINT',  () => { appender.stop(); process.exit(0); });

app.listen(PORT, () => console.log(`simple-rest-node-express running on port ${PORT}`));
