'use strict';
require('dotenv').config();
const OpenSearchWebAppender = require('./opensearch-web-appender');

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

function createAppender() {
  return new OpenSearchWebAppender({
    url:      process.env.OPENSEARCH_URL      || 'https://localhost:9200',
    username: process.env.OPENSEARCH_USERNAME || '',
    password: process.env.OPENSEARCH_PASSWORD || '',
    app:                  process.env.OPENSEARCH_NAME                || 'simple-page-react-nextjs',
    env:                  process.env.OPENSEARCH_ENV                || 'local',
    maxBatchBytes:        parseInt(process.env.OPENSEARCH_BATCH_MAX_BYTES      || '1000000'),
    flushIntervalSeconds: parseInt(process.env.OPENSEARCH_BATCH_FLUSH_INTERVAL || '1'),
    queueSize:            parseInt(process.env.OPENSEARCH_BATCH_QUEUE_SIZE     || '8192'),
    operation:            process.env.OPENSEARCH_BULK_OPERATION || 'create',
    trustAllSsl:          envBool('OPENSEARCH_TRUST_ALL_SSL', true),
    timeout:              parseInt(process.env.OPENSEARCH_TIMEOUT || '10'),
    maxRetries:           parseInt(process.env.OPENSEARCH_MAX_RETRIES || '3'),
    headers:              envHeaders(),
    persistentWriterThread: envBool('OPENSEARCH_PERSISTENT_WRITER_THREAD', true),
  });
}

// Next.js dev 모드 hot-reload 시 중복 인스턴스 방지 — global 싱글톤 사용
let appender;
if (process.env.NODE_ENV === 'production') {
  appender = createAppender();
} else {
  if (!global._opensearchAppender) {
    global._opensearchAppender = createAppender();
  }
  appender = global._opensearchAppender;
}

module.exports = appender;
