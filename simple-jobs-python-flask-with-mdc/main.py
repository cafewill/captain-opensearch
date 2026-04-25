import logging
import os
import random
import time
import uuid
from datetime import datetime, timezone

from apscheduler.schedulers.background import BackgroundScheduler
from dotenv import load_dotenv
from flask import Flask
from opensearch_appender.job_appender import OpenSearchJobAppender

load_dotenv()

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
logger = logging.getLogger(__name__)

FRAMEWORK = 'python-flask-with-mdc'
SYSTEM_DELAY = int(os.getenv('JOB_SYSTEM_DELAY', '3000')) / 1000
MANAGER_DELAY = int(os.getenv('JOB_MANAGER_DELAY', '15000')) / 1000
OPERATOR_DELAY = int(os.getenv('JOB_OPERATOR_DELAY', '20000')) / 1000
RISKY_DELAY = int(os.getenv('JOB_RISKY_DELAY', '60000')) / 1000
SLOW_DELAY = int(os.getenv('JOB_SLOW_DELAY', '60000')) / 1000
P95_THRESHOLD_MS = int(os.getenv('API_MONITORING_P95_THRESHOLD_MS', '800'))
P99_THRESHOLD_MS = int(os.getenv('API_MONITORING_P99_THRESHOLD_MS', '1500'))
SLOW_QUERY_THRESHOLD_MS = int(os.getenv('API_MONITORING_SLOW_QUERY_THRESHOLD_MS', '1200'))

appender = OpenSearchJobAppender(
    url=os.getenv('OPENSEARCH_URL', 'https://localhost:9200'),
    username=os.getenv('OPENSEARCH_USERNAME', ''),
    password=os.getenv('OPENSEARCH_PASSWORD', ''),
    app=os.getenv('OPENSEARCH_NAME', 'simple-jobs-python-flask-with-mdc'),
    env=os.getenv('OPENSEARCH_ENV', 'local'),
    max_batch_bytes=int(os.getenv('OPENSEARCH_BATCH_MAX_BYTES', '1000000')),
    flush_interval_seconds=int(os.getenv('OPENSEARCH_BATCH_FLUSH_INTERVAL', '1')),
    queue_size=int(os.getenv('OPENSEARCH_BATCH_QUEUE_SIZE', '8192')),
)

JOB_PROFILES = {
    'system': ('platform-maintenance', 'cron', 'worker-a1', '00:00-00:05', 'system-checkpoint'),
    'manager': ('control-plane', 'fixed-delay', 'worker-b2', '00:05-00:20', 'manager-checkpoint'),
    'operator': ('runtime-ops', 'fixed-delay', 'worker-c3', '00:20-00:40', 'operator-checkpoint'),
    'risky': ('risk-control', 'fixed-rate', 'worker-r9', '00:40-01:00', 'risky-checkpoint'),
    'slow': ('performance-watch', 'fixed-rate', 'worker-p5', '01:00-01:05', 'slow-method-checkpoint'),
}


def batch_context(job_name, run_id, status, elapsed_ms):
    job_group, trigger_type, worker_node, batch_window, checkpoint_name = JOB_PROFILES[job_name]
    return {
        'observabilityUseCase': 'batch-scheduler-tracking',
        'schedulerName': 'python-scheduler',
        'jobName': job_name,
        'jobGroup': job_group,
        'jobRole': job_group,
        'jobExecutionId': f'job-{run_id[:8]}',
        'triggerType': trigger_type,
        'workerNode': worker_node,
        'batchWindow': batch_window,
        'checkpointName': checkpoint_name,
        'scheduledAt': datetime.now(timezone.utc).isoformat(),
        'runStatus': status,
        'retryAttempt': '0',
        'elapsed_ms': str(elapsed_ms),
    }


def api_performance_context(scenario_name, run_id, response_time):
    slow_query = response_time >= SLOW_QUERY_THRESHOLD_MS
    slow_method = response_time >= 1000
    percentile_target = 'p99' if response_time >= P99_THRESHOLD_MS else 'p95' if response_time >= P95_THRESHOLD_MS else 'normal'
    if response_time < 200:
        latency_bucket = '000-199ms'
    elif response_time < 500:
        latency_bucket = '200-499ms'
    elif response_time < P95_THRESHOLD_MS:
        latency_bucket = '500-799ms'
    elif response_time < P99_THRESHOLD_MS:
        latency_bucket = '800-1499ms'
    else:
        latency_bucket = '1500ms-plus'
    return {
        'observabilityUseCase': 'api-performance-monitoring',
        'traceId': run_id,
        'apiScenario': scenario_name,
        'httpMethod': 'GET',
        'apiRoute': '/api/items/search',
        'queryName': 'items-search-by-status',
        'responseTime': str(response_time),
        'responseTimeMs': str(response_time),
        'latencyBucket': latency_bucket,
        'latencyHeatmapCell': f'{percentile_target}:{latency_bucket}',
        'percentileTarget': percentile_target,
        'p95ThresholdMs': str(P95_THRESHOLD_MS),
        'p99ThresholdMs': str(P99_THRESHOLD_MS),
        'slowQueryThresholdMs': str(SLOW_QUERY_THRESHOLD_MS),
        'slowQueryAlarm': str(slow_query).lower(),
        'slowQueryAlarmRule': f'responseTime >= {SLOW_QUERY_THRESHOLD_MS}ms',
        'slowMethodAlarm': str(slow_method).lower(),
        'slowMethodThresholdMs': '1000',
        'slowMethodAlarmRule': 'responseTime >= 1000ms',
        'dbPoolName': 'items-reader',
        'dashboardPanel': 'api-latency-p95-p99-heatmap',
    }


def log_job(job_name):
    run_id = str(uuid.uuid4())
    fields = {
        'traceId': run_id,
        'framework': FRAMEWORK,
        'appVariant': 'with-mdc',
        'mdcSample': 'enabled',
        'success': 'true',
        **batch_context(job_name, run_id, 'success', 1500),
    }
    msg = f'OS+MDC : Batch scheduler job {fields["jobExecutionId"]} tracked by python flask with MDC [{run_id}]'
    logger.info(msg)
    appender.log('INFO', msg, **fields)


def do_risky_job():
    run_id = str(uuid.uuid4())
    level = 'INFO'
    success = True
    if random.random() >= 0.8:
        time.sleep(random.uniform(3, 10))
        level = random.choice(('WARN', 'ERROR'))
        success = False
    response_time = random.randint(P99_THRESHOLD_MS, 3200) if level == 'ERROR' else random.randint(P95_THRESHOLD_MS, P99_THRESHOLD_MS - 1) if level == 'WARN' else random.randint(80, P95_THRESHOLD_MS - 1)
    fields = {
        'traceId': run_id,
        'jobName': 'risky',
        'jobRole': 'risk-control',
        'framework': FRAMEWORK,
        'appVariant': 'with-mdc',
        'mdcSample': 'enabled',
        'riskOutcome': level.lower(),
        'success': str(success).lower(),
        **batch_context('risky', run_id, 'success' if success else 'degraded', 7310),
        **api_performance_context('risky-search', run_id, response_time),
    }
    msg = f'OS+MDC : Risky job produced {level} API latency sample responseTime={response_time}ms percentile={fields["percentileTarget"]} slowQueryAlarm={fields["slowQueryAlarm"]} by python flask with MDC [{run_id}]'
    logger.warning(msg) if level == 'WARN' else logger.error(msg) if level == 'ERROR' else logger.info(msg)
    appender.log(level, msg, **fields)


def do_slow_job():
    run_id = str(uuid.uuid4())
    response_time = random.randint(20, 100) if random.random() < 0.9 else random.randint(1000, 5000)
    if response_time >= 1000:
        time.sleep(response_time / 1000)
    slow_method = response_time >= 1000
    level = 'WARN' if slow_method else 'INFO'
    fields = {
        'traceId': run_id,
        'jobName': 'slow',
        'jobRole': 'performance-watch',
        'framework': FRAMEWORK,
        'appVariant': 'with-mdc',
        'mdcSample': 'enabled',
        'success': str(not slow_method).lower(),
        **batch_context('slow', run_id, 'slow' if slow_method else 'success', response_time),
        **api_performance_context('slow-method', run_id, response_time),
    }
    msg = f'OS+MDC : Slow job method responseTime={response_time}ms slowMethodAlarm={fields["slowMethodAlarm"]} by python flask with MDC [{run_id}]'
    logger.warning(msg) if slow_method else logger.info(msg)
    appender.log(level, msg, **fields)


app = Flask(__name__)
scheduler = BackgroundScheduler()
scheduler.add_job(lambda: log_job('system'), 'interval', seconds=SYSTEM_DELAY)
scheduler.add_job(lambda: log_job('manager'), 'interval', seconds=MANAGER_DELAY)
scheduler.add_job(lambda: log_job('operator'), 'interval', seconds=OPERATOR_DELAY)
scheduler.add_job(do_risky_job, 'interval', seconds=RISKY_DELAY)
scheduler.add_job(do_slow_job, 'interval', seconds=SLOW_DELAY)
scheduler.start()

if __name__ == '__main__':
    from waitress import serve

    port = int(os.getenv('PORT', '5011'))
    logger.info(f'simple-jobs-python-flask-with-mdc running on port {port}')
    serve(app, host='0.0.0.0', port=port)
