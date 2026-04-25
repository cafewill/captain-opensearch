import base64
import json
import ssl
import urllib.error
import urllib.request


class BulkSendResult:
    def __init__(self, success=False, retryable_items=None, message=''):
        self.success = success
        self.retryable_items = retryable_items or []
        self.message = message


class BulkOnlySender:
    def __init__(
        self,
        url,
        username='',
        password='',
        operation='create',
        trust_all_ssl=True,
        timeout=10,
        headers=None,
    ):
        self.url = self._bulk_url(url)
        self.operation = self._normalize_operation(operation)
        self.timeout = timeout
        self.headers = headers or {}
        self.auth = None
        if username:
            creds = base64.b64encode(f'{username}:{password}'.encode()).decode()
            self.auth = f'Basic {creds}'

        self.ssl_ctx = ssl.create_default_context()
        if trust_all_ssl:
            self.ssl_ctx.check_hostname = False
            self.ssl_ctx.verify_mode = ssl.CERT_NONE

    def action_line(self, index):
        return json.dumps({self.operation: {'_index': index}}) + '\n'

    def send(self, items, body):
        try:
            data = body.encode('utf-8')
            req = urllib.request.Request(self.url, data=data, method='POST')
            req.add_header('Content-Type', 'application/x-ndjson')
            req.add_header('Accept', 'application/json')
            if self.auth:
                req.add_header('Authorization', self.auth)
            for name, value in self.headers.items():
                if name:
                    req.add_header(name, '' if value is None else str(value))

            with urllib.request.urlopen(req, context=self.ssl_ctx, timeout=self.timeout) as response:
                body = response.read().decode('utf-8')
                return self._analyze_bulk_response(items, body)
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', errors='replace')
            if e.code == 429 or e.code >= 500:
                return BulkSendResult(False, items, f'HTTP {e.code}: {self._abbreviate(body)}')
            return BulkSendResult(False, [], f'HTTP {e.code}: {self._abbreviate(body)}')
        except Exception as e:
            return BulkSendResult(False, items, f'send exception: {e}')

    def _analyze_bulk_response(self, items, body):
        if not body:
            return BulkSendResult(True)

        try:
            root = json.loads(body)
        except json.JSONDecodeError:
            return BulkSendResult(True)

        if not root.get('errors', False):
            return BulkSendResult(True)

        retryable = []
        fatal_messages = []
        for i, item_node in enumerate(root.get('items', [])):
            if i >= len(items):
                break
            node = item_node.get('index') or item_node.get('create') or {}
            status = int(node.get('status', 200))
            if status == 429 or status >= 500:
                retryable.append(items[i])
            elif status >= 400:
                error = node.get('error') or {}
                fatal_messages.append(
                    f'[{status}] {error.get("type", "unknown")}: {error.get("reason", "unknown")}'
                )

        if retryable:
            return BulkSendResult(False, retryable, 'partial retryable: ' + ' '.join(fatal_messages))
        if fatal_messages:
            return BulkSendResult(False, [], ' '.join(fatal_messages))
        return BulkSendResult(True)

    def _normalize_operation(self, operation):
        if not operation:
            return 'create'
        value = str(operation).strip().lower()
        return value if value in ('index', 'create') else 'create'

    def _bulk_url(self, url):
        base = (url or '').rstrip('/')
        return base if base.endswith('/_bulk') else f'{base}/_bulk'

    def _abbreviate(self, value):
        return value if len(value) <= 500 else value[:500] + '...'
