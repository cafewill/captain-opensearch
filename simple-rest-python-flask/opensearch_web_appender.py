"""
OpenSearch Web Appender — Flask용 (HTTP 요청 추적 포함)
표준 라이브러리만 사용, 추가 의존성 없음
"""

import base64
import json
import os
import queue
import socket
import ssl
import threading
import time
import uuid
import urllib.request
from datetime import datetime, timezone, timedelta

KST = timezone(timedelta(hours=9), 'KST')


class OpenSearchWebAppender:
    def __init__(
        self,
        scheme='https',
        host='localhost',
        port=9200,
        username='',
        password='',
        app='app',
        env='local',
        max_batch_bytes=1_000_000,
        flush_interval_seconds=1,
        queue_size=8192,
    ):
        self._index       = f'logs-{app}'
        self._app         = app
        self._env         = env
        self._instance_id = os.environ.get('HOSTNAME') or socket.gethostname()
        self._max_bytes   = max_batch_bytes
        self._url         = f'{scheme}://{host}:{port}/_bulk'
        self._queue       = queue.Queue(maxsize=queue_size)
        self._auth        = None
        if username:
            creds      = base64.b64encode(f'{username}:{password}'.encode()).decode()
            self._auth = f'Basic {creds}'
        self._ssl_ctx                  = ssl.create_default_context()
        self._ssl_ctx.check_hostname   = False
        self._ssl_ctx.verify_mode      = ssl.CERT_NONE
        self._running = True
        self._thread  = threading.Thread(target=self._run, args=(flush_interval_seconds,), daemon=True)
        self._thread.start()

    def log(self, level: str, message: str, **extra):
        now   = datetime.now(timezone.utc)
        today = now.strftime('%Y.%m.%d')
        doc   = {
            '@timestamp':       now.isoformat(),
            '@timestamp_local': datetime.now().astimezone().isoformat(),
            '@timestamp_kst':   now.astimezone(KST).isoformat(),
            'level':       level,
            'message':     message,
            'app':         self._app,
            'env':         self._env,
            'instance_id': self._instance_id,
            **extra,
        }
        try:
            self._queue.put_nowait((f'{self._index}-{today}', doc))
        except queue.Full:
            pass

    def before_request(self, request):
        """Flask before_request 훅에서 호출 — trace_id, start_time 주입"""
        request.trace_id  = request.headers.get('X-Request-ID') or str(uuid.uuid4())
        request.start_time = time.time()

    def after_request(self, request, response):
        """Flask after_request 훅에서 호출 — 접근 로그 기록"""
        duration_ms = int((time.time() - request.start_time) * 1000)
        ts = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]
        print(f'[{ts}] {request.method} {request.path} → {response.status_code} ({duration_ms}ms) [{request.trace_id}]', flush=True)
        self.log(
            'INFO',
            f'{request.method} {request.path} → {response.status_code}',
            trace_id    = request.trace_id,
            http_method = request.method,
            http_path   = request.path,
            client_ip   = request.remote_addr,
            http_status = response.status_code,
            duration_ms = duration_ms,
        )
        response.headers['X-Request-ID'] = request.trace_id
        return response

    def stop(self):
        self._running = False
        self._flush()

    def _run(self, interval: int):
        while self._running:
            time.sleep(interval)
            self._flush()

    def _flush(self):
        items = []
        try:
            while True:
                items.append(self._queue.get_nowait())
        except queue.Empty:
            pass
        if not items:
            return
        bulk = ''
        for idx, doc in items:
            bulk += json.dumps({'index': {'_index': idx}}) + '\n'
            bulk += json.dumps(doc) + '\n'
            if len(bulk.encode()) >= self._max_bytes:
                self._send(bulk)
                bulk = ''
        if bulk:
            self._send(bulk)

    def _send(self, body: str):
        try:
            data = body.encode('utf-8')
            req  = urllib.request.Request(self._url, data=data, method='POST')
            req.add_header('Content-Type', 'application/json; charset=UTF-8')
            if self._auth:
                req.add_header('Authorization', self._auth)
            with urllib.request.urlopen(req, context=self._ssl_ctx, timeout=10):
                pass
        except Exception as e:
            print(f'[OpenSearch] 전송 실패: {e}', flush=True)
