import os
import uuid
import logging
import time
from functools import wraps

from dotenv import load_dotenv
from flask import Flask
from apscheduler.schedulers.background import BackgroundScheduler
from opensearch_appender.job_appender import OpenSearchJobAppender

load_dotenv()

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
logger = logging.getLogger(__name__)

SYSTEM_DELAY   = int(os.getenv('JOB_SYSTEM_DELAY',   '3000'))  / 1000
MANAGER_DELAY  = int(os.getenv('JOB_MANAGER_DELAY',  '15000')) / 1000
OPERATOR_DELAY = int(os.getenv('JOB_OPERATOR_DELAY', '20000')) / 1000

appender = OpenSearchJobAppender(
    scheme=os.getenv('OPENSEARCH_SCHEME', 'https'),
    host=os.getenv('OPENSEARCH_HOST', 'localhost'),
    port=int(os.getenv('OPENSEARCH_PORT', '9200')),
    username=os.getenv('OPENSEARCH_USERNAME', ''),
    password=os.getenv('OPENSEARCH_PASSWORD', ''),
    app=os.getenv('OPENSEARCH_NAME', 'simple-jobs-python-flask'),
    env=os.getenv('OPENSEARCH_ENV', 'local'),
    max_batch_bytes=int(os.getenv('OPENSEARCH_BATCH_MAX_BYTES', '1000000')),
    flush_interval_seconds=int(os.getenv('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size=int(os.getenv('OPENSEARCH_BATCH_QUEUE_SIZE', '8192')),
)


def with_retry(max_attempts=3, backoff=1.0):
    def decorator(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            for attempt in range(1, max_attempts + 1):
                try:
                    return fn(*args, **kwargs)
                except Exception as e:
                    if attempt == max_attempts:
                        logger.error(f'[RECOVER] job failed after {max_attempts} retries: {e}')
                        return
                    time.sleep(backoff)
        return wrapper
    return decorator


@with_retry(max_attempts=3, backoff=1.0)
def do_system_job():
    msg = f'OS : Just do system job by python flask [{uuid.uuid4()}]'
    logger.info(msg)
    appender.log('INFO', msg, job='system-job')


@with_retry(max_attempts=3, backoff=1.0)
def do_manager_job():
    msg = f'OS : Just do manager job by python flask [{uuid.uuid4()}]'
    logger.info(msg)
    appender.log('INFO', msg, job='manager-job')


@with_retry(max_attempts=3, backoff=1.0)
def do_operator_job():
    msg = f'OS : Just do operator job by python flask [{uuid.uuid4()}]'
    logger.info(msg)
    appender.log('INFO', msg, job='operator-job')


app = Flask(__name__)

scheduler = BackgroundScheduler()
scheduler.add_job(do_system_job,   'interval', seconds=SYSTEM_DELAY)
scheduler.add_job(do_manager_job,  'interval', seconds=MANAGER_DELAY)
scheduler.add_job(do_operator_job, 'interval', seconds=OPERATOR_DELAY)
scheduler.start()

if __name__ == '__main__':
    from waitress import serve
    port = int(os.getenv('PORT', '5001'))
    logger.info(f'simple-jobs-python-flask running on port {port}')
    serve(app, host='0.0.0.0', port=port)
