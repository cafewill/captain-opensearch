package com.cube.simple.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(value = "job.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleService {
	private final BatchSchedulerTrackingMdcService batchSchedulerTrackingMdcService;
	private final ApiPerformanceMonitoringMdcService apiPerformanceMonitoringMdcService;

	public ScheduleService(
			BatchSchedulerTrackingMdcService batchSchedulerTrackingMdcService,
			ApiPerformanceMonitoringMdcService apiPerformanceMonitoringMdcService) {
		this.batchSchedulerTrackingMdcService = batchSchedulerTrackingMdcService;
		this.apiPerformanceMonitoringMdcService = apiPerformanceMonitoringMdcService;
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.system.delay:3000}")
	public void doSystemJob() {
		logJobWithMdc("system", "platform", "spring-maven-with-mdc");
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.manager.delay:15000}")
	public void doManagerJob() {
		logJobWithMdc("manager", "control", "spring-maven-with-mdc");
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.operator.delay:20000}")
	public void doOperatorJob() {
		logJobWithMdc("operator", "runtime", "spring-maven-with-mdc");
	}

	@Scheduled(fixedRateString = "${job.risky.delay:60000}")
	public void doRiskyJob() throws InterruptedException {
		logRiskyJobWithMdc("spring-maven-with-mdc");
	}

	@Scheduled(fixedRateString = "${job.slow.delay:60000}")
	public void doSlowJob() throws InterruptedException {
		logSlowJobWithMdc("spring-maven-with-mdc");
	}

	@Recover
	public void recoverJob(Exception e) {
		log.error("OS+MDC : scheduled job failed after retries - {}", e.getMessage(), e);
	}

	private void logJobWithMdc(String jobName, String jobRole, String framework) {
		String runId = UUID.randomUUID().toString();
		Map<String, String> batchFields = batchSchedulerTrackingMdcService.createContext(
				jobName, runId, "success", resolveElapsedMs(jobName));
		MDC.put("traceId", runId);
		MDC.put("jobName", jobName);
		MDC.put("jobRole", jobRole);
		MDC.put("framework", framework);
		MDC.put("appVariant", "with-mdc");
		MDC.put("mdcSample", "enabled");
		putAll(batchFields);
		MDC.put("success", "true");
		try {
			String message = String.format(
					"OS+MDC : Batch scheduler job %s tracked by spring maven with MDC [%s]",
					batchFields.get("jobExecutionId"),
					runId);
			log.info(message);
		} finally {
			MDC.clear();
		}
	}

	private void logRiskyJobWithMdc(String framework) throws InterruptedException {
		String runId = UUID.randomUUID().toString();
		int chance = ThreadLocalRandom.current().nextInt(100);
		String level = "INFO";
		boolean success = true;
		if (chance >= 80) {
			Thread.sleep(ThreadLocalRandom.current().nextLong(3000, 10001));
			level = ThreadLocalRandom.current().nextBoolean() ? "WARN" : "ERROR";
			success = false;
		}

		long elapsedMs = resolveElapsedMs("risky");
		ApiPerformanceMonitoringMdcService.ApiPerformanceContext apiContext =
				apiPerformanceMonitoringMdcService.createContext("risky-search", runId, level);
		Map<String, String> batchFields = batchSchedulerTrackingMdcService.createContext(
				"risky", runId, success ? "success" : "degraded", elapsedMs);
		MDC.put("traceId", runId);
		MDC.put("jobName", "risky");
		MDC.put("jobRole", "risk-control");
		MDC.put("framework", framework);
		MDC.put("appVariant", "with-mdc");
		MDC.put("mdcSample", "enabled");
		MDC.put("riskOutcome", level.toLowerCase());
		putAll(batchFields);
		putAll(apiContext.fields());
		MDC.put("success", String.valueOf(success));
		try {
			String message = String.format(
					"OS+MDC : Risky job produced %s API latency sample responseTime=%dms percentile=%s slowQueryAlarm=%s by spring maven with MDC [%s]",
					level,
					apiContext.responseTime(),
					apiContext.percentileBand(),
					apiContext.slowQuery(),
					runId);
			switch (level) {
				case "WARN" -> log.warn(message);
				case "ERROR" -> log.error(message);
				default -> log.info(message);
			}
		} finally {
			MDC.clear();
		}
	}

	private void logSlowJobWithMdc(String framework) throws InterruptedException {
		String runId = UUID.randomUUID().toString();
		int responseTime = resolveSlowJobResponseTime();
		if (responseTime >= 1000) {
			Thread.sleep(responseTime);
		}

		ApiPerformanceMonitoringMdcService.ApiPerformanceContext apiContext =
				apiPerformanceMonitoringMdcService.createSlowMethodContext("slow-method", runId, responseTime);
		Map<String, String> batchFields = batchSchedulerTrackingMdcService.createContext(
				"slow", runId, apiContext.slowMethod() ? "slow" : "success", responseTime);
		MDC.put("traceId", runId);
		MDC.put("jobName", "slow");
		MDC.put("jobRole", "performance-watch");
		MDC.put("framework", framework);
		MDC.put("appVariant", "with-mdc");
		MDC.put("mdcSample", "enabled");
		putAll(batchFields);
		putAll(apiContext.fields());
		MDC.put("success", String.valueOf(!apiContext.slowMethod()));
		try {
			String message = String.format(
					"OS+MDC : Slow job method responseTime=%dms slowMethodAlarm=%s by spring maven with MDC [%s]",
					apiContext.responseTime(),
					apiContext.slowMethod(),
					runId);
			if (apiContext.slowMethod()) {
				log.warn(message);
			} else {
				log.info(message);
			}
		} finally {
			MDC.clear();
		}
	}

	private void putAll(Map<String, String> fields) {
		for (Map.Entry<String, String> entry : fields.entrySet()) {
			MDC.put(entry.getKey(), entry.getValue());
		}
	}

	private long resolveElapsedMs(String jobName) {
		return switch (jobName) {
			case "system" -> 1420L;
			case "manager" -> 2780L;
			case "operator" -> 1935L;
			case "risky" -> 7310L;
			case "slow" -> 95L;
			default -> 1500L;
		};
	}

	private int resolveSlowJobResponseTime() {
		if (ThreadLocalRandom.current().nextInt(100) < 90) {
			return ThreadLocalRandom.current().nextInt(20, 101);
		}
		return ThreadLocalRandom.current().nextInt(1000, 5001);
	}

}
