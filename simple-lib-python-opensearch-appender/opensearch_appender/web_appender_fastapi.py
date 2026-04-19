import atexit
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
        self._retry       = []  # 전송 실패 항목 — 다음 _flush() 에서 우선 처리
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
        atexit.register(self.stop)  # 프로세스 종료 시 미전송 로그 플러시

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
        """FastAPI/Starlette before_request 훅에서 호출 — trace_id, start_time 주입"""
        request.state.trace_id   = request.headers.get('X-Request-ID') or str(uuid.uuid4())
        request.state.start_time = time.time()

    def after_request(self, request, response):
        """FastAPI/Starlette after_request 훅에서 호출 — 접근 로그 기록"""
        duration_ms = int((time.time() - request.state.start_time) * 1000)
        ts = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]
        print(f'[{ts}] {request.method} {request.url.path} → {response.status_code} ({duration_ms}ms) [{request.state.trace_id}]', flush=True)
        self.log(
            'INFO',
            f'{request.method} {request.url.path} → {response.status_code}',
            trace_id    = request.state.trace_id,
            http_method = request.method,
            http_path   = request.url.path,
            client_ip   = request.client.host if request.client else '',
            http_status = response.status_code,
            duration_ms = duration_ms,
        )
        response.headers['X-Request-ID'] = request.state.trace_id
        return response

    def stop(self):
        self._running = False
        self._flush()

    def _run(self, interval: int):
        while self._running:
            time.sleep(interval)
            self._flush()

    def _flush(self):
        # 이전 전송 실패 항목을 우선 처리 후 새 항목 추가
        items = self._retry
        self._retry = []
        try:
            while True:
                items.append(self._queue.get_nowait())
        except queue.Empty:
            pass
        if not items:
            return
        bulk  = ''
        batch = []
        for item in items:
            meta    = json.dumps({'index': {'_index': item[0]}}) + '\n'
            doc_str = json.dumps(item[1]) + '\n'
            addition = len((meta + doc_str).encode())
            # 추가 전 초과 여부 확인 — 배치에 내용이 있을 때만 먼저 전송
            if bulk and len(bulk.encode()) + addition > self._max_bytes:
                self._send(batch, bulk)
                bulk  = ''
                batch = []
            bulk  += meta + doc_str
            batch.append(item)
        if bulk:
            self._send(batch, bulk)

    def _send(self, items: list, body: str):
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
            # 큐 여유가 있으면 다음 flush 에서 재시도
            if len(self._retry) + len(items) <= self._queue.maxsize:
                self._retry = items + self._retry
            else:
                print(f'[OpenSearch] {len(items)}건 유실 (재시도 용량 초과)', flush=True)
