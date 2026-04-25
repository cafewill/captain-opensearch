import os
import uuid
import logging
import time
import random
import json
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
RISKY_DELAY    = int(os.getenv('JOB_RISKY_DELAY',    '60000')) / 1000


def env_bool(name, default):
    return os.getenv(name, str(default)).lower() in ('1', 'true', 'yes', 'y')


def env_headers():
    return json.loads(os.getenv('OPENSEARCH_HEADERS', '{}'))


appender = OpenSearchJobAppender(
    url=os.getenv('OPENSEARCH_URL', 'https://localhost:9200'),
    username=os.getenv('OPENSEARCH_USERNAME', ''),
    password=os.getenv('OPENSEARCH_PASSWORD', ''),
    app=os.getenv('OPENSEARCH_NAME', 'simple-jobs-python-flask'),
    env=os.getenv('OPENSEARCH_ENV', 'local'),
    max_batch_bytes=int(os.getenv('OPENSEARCH_BATCH_MAX_BYTES', '1000000')),
    flush_interval_seconds=int(os.getenv('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size=int(os.getenv('OPENSEARCH_BATCH_QUEUE_SIZE', '8192')),
    operation=os.getenv('OPENSEARCH_BULK_OPERATION', 'create'),
    trust_all_ssl=env_bool('OPENSEARCH_TRUST_ALL_SSL', True),
    timeout=int(os.getenv('OPENSEARCH_TIMEOUT', '10')),
    max_retries=int(os.getenv('OPENSEARCH_MAX_RETRIES', '3')),
    headers=env_headers(),
    persistent_writer_thread=env_bool('OPENSEARCH_PERSISTENT_WRITER_THREAD', True),
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


def do_risky_job():
    run_id = uuid.uuid4()
    if random.random() < 0.8:
        msg = f'OS : Risky job completed normally by python flask [{run_id}]'
        logger.info(msg)
        appender.log('INFO', msg, job='risky-job')
        return

    time.sleep(random.uniform(3, 10))
    level = random.choice(('WARN', 'ERROR'))
    msg = f'OS : Risky job found unstable condition by python flask [{run_id}]'
    if level == 'WARN':
        logger.warning(msg)
    else:
        logger.error(msg)
    appender.log(level, msg, job='risky-job')


app = Flask(__name__)

scheduler = BackgroundScheduler()
scheduler.add_job(do_system_job,   'interval', seconds=SYSTEM_DELAY)
scheduler.add_job(do_manager_job,  'interval', seconds=MANAGER_DELAY)
scheduler.add_job(do_operator_job, 'interval', seconds=OPERATOR_DELAY)
scheduler.add_job(do_risky_job,    'interval', seconds=RISKY_DELAY)
scheduler.start()

if __name__ == '__main__':
    from waitress import serve
    port = int(os.getenv('PORT', '5001'))
    logger.info(f'simple-jobs-python-flask running on port {port}')
    serve(app, host='0.0.0.0', port=port)
