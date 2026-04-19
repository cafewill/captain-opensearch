import os
import signal
import sys

from dotenv import load_dotenv
load_dotenv()

from flask import Flask, request, jsonify
import db
from opensearch_appender.web_appender_flask import OpenSearchWebAppender

app = Flask(__name__)

appender = OpenSearchWebAppender(
    scheme                = os.environ.get('OPENSEARCH_SCHEME',   'https'),
    host                  = os.environ.get('OPENSEARCH_HOST',     'localhost'),
    port                  = int(os.environ.get('OPENSEARCH_PORT', '9200')),
    username              = os.environ.get('OPENSEARCH_USERNAME', ''),
    password              = os.environ.get('OPENSEARCH_PASSWORD', ''),
    app                   = os.environ.get('OPENSEARCH_NAME',     'simple-rest-python-flask'),
    env                   = os.environ.get('OPENSEARCH_ENV',      'local'),
    max_batch_bytes       = int(os.environ.get('OPENSEARCH_BATCH_MAX_BYTES',      '1000000')),
    flush_interval_seconds= int(os.environ.get('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size            = int(os.environ.get('OPENSEARCH_BATCH_QUEUE_SIZE',     '8192')),
)


@app.before_request
def _before():
    appender.before_request(request)


@app.after_request
def _after(response):
    return appender.after_request(request, response)


# ── CRUD ──────────────────────────────────────────────────────────────────────

@app.get('/api/items')
def find_all():
    items = db.find_all()
    appender.log('INFO', f'GET /api/items → {len(items)}건', trace_id=request.trace_id)
    return jsonify({'status': True, 'message': '조회 성공', 'data': items})


@app.get('/api/items/<int:item_id>')
def find_by_id(item_id):
    item = db.find_by_id(item_id)
    if item is None:
        return jsonify({'status': False, 'message': 'Item not found'}), 404
    return jsonify({'status': True, 'message': '조회 성공', 'data': item})


@app.post('/api/items')
def create():
    body = request.get_json(silent=True) or {}
    name  = body.get('name', '').strip()
    price = body.get('price')
    if not name or price is None:
        return jsonify({'status': False, 'message': 'name, price 필수'}), 400
    item = db.insert(name, body.get('description', ''), int(price))
    appender.log('INFO', f'POST /api/items → id={item["id"]}', trace_id=request.trace_id)
    return jsonify({'status': True, 'message': '등록 성공', 'data': item}), 201


@app.put('/api/items/<int:item_id>')
def update(item_id):
    if db.find_by_id(item_id) is None:
        return jsonify({'status': False, 'message': 'Item not found'}), 404
    body  = request.get_json(silent=True) or {}
    name  = body.get('name', '').strip()
    price = body.get('price')
    if not name or price is None:
        return jsonify({'status': False, 'message': 'name, price 필수'}), 400
    item = db.update(item_id, name, body.get('description', ''), int(price))
    return jsonify({'status': True, 'message': '수정 성공', 'data': item})


@app.delete('/api/items/<int:item_id>')
def remove(item_id):
    if not db.remove(item_id):
        return jsonify({'status': False, 'message': 'Item not found'}), 404
    return jsonify({'status': True, 'message': '삭제 성공', 'data': None})


# ── 종료 처리 ─────────────────────────────────────────────────────────────────

def _shutdown(sig, frame):
    appender.stop()
    sys.exit(0)

signal.signal(signal.SIGTERM, _shutdown)
signal.signal(signal.SIGINT,  _shutdown)

if __name__ == '__main__':
    from waitress import serve
    port = int(os.environ.get('PORT', '5201'))
    print(f'simple-rest-python-flask running on port {port}')
    serve(app, host='0.0.0.0', port=port)
