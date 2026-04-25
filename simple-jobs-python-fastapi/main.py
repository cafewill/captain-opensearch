import os
import uuid
import asyncio
import logging
import random
import json
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
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
    app=os.getenv('OPENSEARCH_NAME', 'simple-jobs-python-fastapi'),
    env=os.getenv('OPENSEARCH_ENV', 'local'),
    max_batch_bytes=int(os.getenv('OPENSEARCH_BATCH_MAX_BYTES', '1000000')),
    flush_interval_seconds=int(os.getenv('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size=int(os.getenv('OPENSEARCH_BATCH_QUEUE_SIZE', '8192')),
    operation=os.getenv('OPENSEARCH_BULK_OPERATION', 'create'),
    trust_all_ssl=env_bool('OPENSEARCH_TRUST_ALL_SSL', True),
    timeout=int(os.getenv('OPENSEARCH_TIMEOUT', '10')),
    max_retries=int(os.getenv('OPENSEARCH_MAX_RETRIES', '3')),
    headers=env_headers(),
)


async def with_retry(fn, max_attempts=3, backoff=1.0):
    for attempt in range(1, max_attempts + 1):
        try:
            await fn()
            return
        except Exception as e:
            if attempt == max_attempts:
                logger.error(f'[RECOVER] job failed after {max_attempts} retries: {e}')
                return
            await asyncio.sleep(backoff)


async def job_loop(fn, delay: float):
    while True:
        await with_retry(fn)
        await asyncio.sleep(delay)


async def fixed_rate_job_loop(fn, delay: float):
    while True:
        asyncio.create_task(with_retry(fn))
        await asyncio.sleep(delay)


async def do_system_job():
    msg = f'OS : Just do system job by python fastapi [{uuid.uuid4()}]'
    logger.info(msg)
    appender.log('INFO', msg, job='system-job')


async def do_manager_job():
    msg = f'OS : Just do manager job by python fastapi [{uuid.uuid4()}]'
    logger.info(msg)
    appender.log('INFO', msg, job='manager-job')


async def do_operator_job():
    msg = f'OS : Just do operator job by python fastapi [{uuid.uuid4()}]'
    logger.info(msg)
    appender.log('INFO', msg, job='operator-job')


async def do_risky_job():
    run_id = uuid.uuid4()
    if random.random() < 0.8:
        msg = f'OS : Risky job completed normally by python fastapi [{run_id}]'
        logger.info(msg)
        appender.log('INFO', msg, job='risky-job')
        return

    await asyncio.sleep(random.uniform(3, 10))
    level = random.choice(('WARN', 'ERROR'))
    msg = f'OS : Risky job found unstable condition by python fastapi [{run_id}]'
    if level == 'WARN':
        logger.warning(msg)
    else:
        logger.error(msg)
    appender.log(level, msg, job='risky-job')


@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(job_loop(do_system_job,   SYSTEM_DELAY))
    asyncio.create_task(job_loop(do_manager_job,  MANAGER_DELAY))
    asyncio.create_task(job_loop(do_operator_job, OPERATOR_DELAY))
    asyncio.create_task(fixed_rate_job_loop(do_risky_job, RISKY_DELAY))
    yield


app = FastAPI(lifespan=lifespan)

if __name__ == '__main__':
    import uvicorn
    port = int(os.getenv('PORT', '5002'))
    logger.info(f'simple-jobs-python-fastapi running on port {port}')
    uvicorn.run(app, host='0.0.0.0', port=port)
