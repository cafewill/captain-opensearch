import os
import signal
import sys
import json
from contextlib import asynccontextmanager
from typing import Optional

from dotenv import load_dotenv
load_dotenv()

from fastapi import FastAPI, Request, Response, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
import db
from opensearch_appender.web_appender_fastapi import OpenSearchWebAppender


def env_bool(name, default):
    return os.environ.get(name, str(default)).lower() in ('1', 'true', 'yes', 'y')


def env_headers():
    return json.loads(os.environ.get('OPENSEARCH_HEADERS', '{}'))


appender = OpenSearchWebAppender(
    url                   = os.environ.get('OPENSEARCH_URL',      'https://localhost:9200'),
    username              = os.environ.get('OPENSEARCH_USERNAME', ''),
    password              = os.environ.get('OPENSEARCH_PASSWORD', ''),
    app                   = os.environ.get('OPENSEARCH_NAME',     'simple-rest-python-fastapi'),
    env                   = os.environ.get('OPENSEARCH_ENV',      'local'),
    max_batch_bytes       = int(os.environ.get('OPENSEARCH_BATCH_MAX_BYTES',      '1000000')),
    flush_interval_seconds= int(os.environ.get('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size            = int(os.environ.get('OPENSEARCH_BATCH_QUEUE_SIZE',     '8192')),
    operation             = os.environ.get('OPENSEARCH_BULK_OPERATION', 'create'),
    trust_all_ssl         = env_bool('OPENSEARCH_TRUST_ALL_SSL', True),
    timeout               = int(os.environ.get('OPENSEARCH_TIMEOUT', '10')),
    max_retries           = int(os.environ.get('OPENSEARCH_MAX_RETRIES', '3')),
    headers               = env_headers(),
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    appender.stop()


application = FastAPI(lifespan=lifespan)


# ── 미들웨어 ──────────────────────────────────────────────────────────────────

@application.middleware('http')
async def access_log_middleware(request: Request, call_next):
    appender.before_request(request)
    response = await call_next(request)
    return appender.after_request(request, response)


# ── DTO ───────────────────────────────────────────────────────────────────────

class ItemRequest(BaseModel):
    name:        str           = Field(..., min_length=1)
    description: Optional[str] = ''
    price:       int           = Field(...)


# ── CRUD ──────────────────────────────────────────────────────────────────────

@application.get('/api/items')
def find_all(request: Request):
    items = db.find_all()
    appender.log('INFO', f'GET /api/items → {len(items)}건', trace_id=request.state.trace_id)
    return {'status': True, 'message': '조회 성공', 'data': items}


@application.get('/api/items/{item_id}')
def find_by_id(item_id: int):
    item = db.find_by_id(item_id)
    if item is None:
        raise HTTPException(status_code=404, detail='Item not found')
    return {'status': True, 'message': '조회 성공', 'data': item}


@application.post('/api/items', status_code=201)
def create(body: ItemRequest, request: Request):
    item = db.insert(body.name, body.description or '', body.price)
    appender.log('INFO', f'POST /api/items → id={item["id"]}', trace_id=request.state.trace_id)
    return {'status': True, 'message': '등록 성공', 'data': item}


@application.put('/api/items/{item_id}')
def update(item_id: int, body: ItemRequest):
    if db.find_by_id(item_id) is None:
        raise HTTPException(status_code=404, detail='Item not found')
    item = db.update(item_id, body.name, body.description or '', body.price)
    return {'status': True, 'message': '수정 성공', 'data': item}


@application.delete('/api/items/{item_id}')
def remove(item_id: int):
    if not db.remove(item_id):
        raise HTTPException(status_code=404, detail='Item not found')
    return {'status': True, 'message': '삭제 성공', 'data': None}


# ── 예외 핸들러 ───────────────────────────────────────────────────────────────

@application.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={'status': False, 'message': exc.detail})


# ── 종료 처리 ─────────────────────────────────────────────────────────────────

def _shutdown(sig, frame):
    appender.stop()
    sys.exit(0)

signal.signal(signal.SIGTERM, _shutdown)
signal.signal(signal.SIGINT,  _shutdown)


if __name__ == '__main__':
    import uvicorn
    port = int(os.environ.get('PORT', '5202'))
    print(f'simple-rest-python-fastapi running on port {port}')
    uvicorn.run(application, host='0.0.0.0', port=port)
