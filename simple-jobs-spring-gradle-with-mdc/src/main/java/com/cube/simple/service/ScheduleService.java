package com.cube.simple.service;

import java.util.UUID;

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

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.system.delay:3000}")
	public void doSystemJob() {
		logJobWithMdc("system", "platform", "spring-gradle-with-mdc");
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.manager.delay:15000}")
	public void doManagerJob() {
		logJobWithMdc("manager", "control", "spring-gradle-with-mdc");
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.operator.delay:20000}")
	public void doOperatorJob() {
		logJobWithMdc("operator", "runtime", "spring-gradle-with-mdc");
	}

	@Recover
	public void recoverJob(Exception e) {
		log.error("OS+MDC : scheduled job failed after retries - {}", e.getMessage(), e);
	}

	private void logJobWithMdc(String jobName, String jobRole, String framework) {
		String runId = UUID.randomUUID().toString();
		MDC.put("traceId", runId);
		MDC.put("jobName", jobName);
		MDC.put("jobRole", jobRole);
		MDC.put("framework", framework);
		MDC.put("appVariant", "with-mdc");
		MDC.put("mdcSample", "enabled");
		try {
			String message = String.format("OS+MDC : Just do %s job by spring gradle with MDC [%s]", jobName, runId);
			log.info(message);
		} finally {
			MDC.clear();
		}
	}
}
