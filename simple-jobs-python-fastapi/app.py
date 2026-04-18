import os
import uuid
import asyncio
import logging
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
from opensearch_job_appender import OpenSearchJobAppender

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
    app=os.getenv('OPENSEARCH_NAME', 'simple-jobs-python-fastapi'),
    env=os.getenv('OPENSEARCH_ENV', 'local'),
    max_batch_bytes=int(os.getenv('OPENSEARCH_BATCH_MAX_BYTES', '1000000')),
    flush_interval_seconds=int(os.getenv('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size=int(os.getenv('OPENSEARCH_BATCH_QUEUE_SIZE', '8192')),
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


@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(job_loop(do_system_job,   SYSTEM_DELAY))
    asyncio.create_task(job_loop(do_manager_job,  MANAGER_DELAY))
    asyncio.create_task(job_loop(do_operator_job, OPERATOR_DELAY))
    yield


app = FastAPI(lifespan=lifespan)

if __name__ == '__main__':
    import uvicorn
    port = int(os.getenv('PORT', '5002'))
    logger.info(f'simple-jobs-python-fastapi running on port {port}')
    uvicorn.run(app, host='0.0.0.0', port=port)
