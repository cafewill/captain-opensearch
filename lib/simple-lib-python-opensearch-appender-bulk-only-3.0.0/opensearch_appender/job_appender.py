import atexit
import json
import os
import queue
import socket
import threading
import time
from datetime import datetime, timezone, timedelta

from .bulk_sender import BulkOnlySender

KST = timezone(timedelta(hours=9), 'KST')


class OpenSearchJobAppender:
    def __init__(
        self,
        url='https://localhost:9200',
        username='',
        password='',
        app='app',
        env='local',
        max_batch_bytes=1_000_000,
        flush_interval_seconds=1,
        queue_size=8192,
        operation='create',
        trust_all_ssl=True,
        timeout=10,
        max_retries=3,
        headers=None,
    ):
        self._index       = f'logs-{app}'
        self._app         = app
        self._env         = env
        self._instance_id = os.environ.get('HOSTNAME') or socket.gethostname()
        self._max_bytes   = max_batch_bytes
        self._max_retries = max(0, int(max_retries))
        self._queue       = queue.Queue(maxsize=queue_size)
        self._retry       = []  # 전송 실패 항목 — 다음 _flush() 에서 우선 처리
        self._sender      = BulkOnlySender(
            url=url,
            username=username,
            password=password,
            operation=operation,
            trust_all_ssl=trust_all_ssl,
            timeout=timeout,
            headers=headers,
        )
        self._running = True
        self._thread  = threading.Thread(
            target=self._run, args=(flush_interval_seconds,), daemon=True
        )
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
            meta    = self._sender.action_line(item[0])
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
        current = items
        for attempt in range(self._max_retries + 1):
            result = self._sender.send(current, body)
            if result.success:
                return
            if not result.retryable_items:
                print(f'[OpenSearch] 전송 실패: {result.message}', flush=True)
                return
            current = result.retryable_items
            body = self._build_payload(current)
            if attempt < self._max_retries:
                time.sleep(0.1)

        print(f'[OpenSearch] 전송 재시도 초과: {result.message}', flush=True)
        if len(self._retry) + len(current) <= self._queue.maxsize:
            self._retry = current + self._retry
        else:
            print(f'[OpenSearch] {len(current)}건 유실 (재시도 용량 초과)', flush=True)

    def _build_payload(self, items: list):
        bulk = ''
        for index, doc in items:
            bulk += self._sender.action_line(index)
            bulk += json.dumps(doc) + '\n'
        return bulk
