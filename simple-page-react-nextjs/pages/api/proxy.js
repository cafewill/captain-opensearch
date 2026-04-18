'use strict';
const { randomUUID } = require('crypto');
const appender = require('../../lib/appender');

export default async function handler(req, res) {
  const { url } = req.query;
  if (!url) return res.status(400).json({ error: 'url 파라미터 필수' });

  const traceId = req.headers['x-request-id'] || randomUUID();
  res.setHeader('X-Request-ID', traceId);
  const start = Date.now();

  let targetPath = '/unknown';
  let targetPort = 0;
  try {
    const parsed = new URL(url);
    targetPath = parsed.pathname;
    targetPort = parseInt(parsed.port);
  } catch (_) {}

  try {
    const options = {
      method:  req.method,
      headers: { 'Content-Type': 'application/json' },
    };
    if (req.method !== 'GET' && req.method !== 'DELETE') {
      options.body = JSON.stringify(req.body);
    }

    const upstream = await fetch(url, options);
    const text     = await upstream.text();
    let data;
    try { data = JSON.parse(text); } catch { data = text; }

    const duration = Date.now() - start;
    const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
    console.log(`[${ts}] ${req.method} :${targetPort}${targetPath} → ${upstream.status} (${duration}ms) [${traceId}]`);

    appender.log('INFO', `${req.method} :${targetPort}${targetPath} → ${upstream.status}`, {
      trace_id:    traceId,
      http_method: req.method,
      http_path:   targetPath,
      target_port: targetPort,
      http_status: upstream.status,
      duration_ms: duration,
      client_ip:   (req.headers['x-forwarded-for'] || req.socket?.remoteAddress || '').split(',')[0].trim(),
    });

    res.status(upstream.status).json(data);
  } catch (err) {
    const duration = Date.now() - start;
    const ts = new Date().toISOString().replace('T', ' ').slice(0, 23);
    console.log(`[${ts}] ${req.method} :${targetPort}${targetPath} → ERR (${duration}ms) [${traceId}] ${err.message}`);

    appender.log('ERROR', `백엔드 연결 실패: ${err.message}`, {
      trace_id:    traceId,
      http_method: req.method,
      http_path:   targetPath,
      target_port: targetPort,
      duration_ms: duration,
      client_ip:   (req.headers['x-forwarded-for'] || req.socket?.remoteAddress || '').split(',')[0].trim(),
    });

    res.status(502).json({ error: `백엔드 연결 실패: ${err.message}` });
  }
}
