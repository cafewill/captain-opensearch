"""
OpenSearch _bulk API 어펜더 — Python 표준 라이브러리만 사용 (추가 의존성 없음)

다른 프로젝트 반영 시 이 파일 하나만 복사
  1. 프로젝트 루트에 복사
  2. .env 에 OPENSEARCH_* 환경변수 추가
  3. from opensearch_appender import OpenSearchAppender 후 인스턴스 생성
"""

import base64
import json
import os
import queue
import socket
import ssl
import threading
import urllib.request
from datetime import datetime, timezone, timedelta

KST = timezone(timedelta(hours=9), 'KST')


class OpenSearchJobAppender:
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
        self._thread = threading.Thread(
            target=self._run, args=(flush_interval_seconds,), daemon=True
        )
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

    def _run(self, interval: int):
        import time
        while True:
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
