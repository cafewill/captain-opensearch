package com.cube.simple.service;

import java.util.Map;
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
	private final RoboticsResearchMdcService roboticsResearchMdcService;
	private final AutomotiveEngineeringMdcService automotiveEngineeringMdcService;

	public ScheduleService(
			RoboticsResearchMdcService roboticsResearchMdcService,
			AutomotiveEngineeringMdcService automotiveEngineeringMdcService) {
		this.roboticsResearchMdcService = roboticsResearchMdcService;
		this.automotiveEngineeringMdcService = automotiveEngineeringMdcService;
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

	@Recover
	public void recoverJob(Exception e) {
		log.error("OS+MDC : scheduled job failed after retries - {}", e.getMessage(), e);
	}

	private void logJobWithMdc(String jobName, String jobRole, String framework) {
		String runId = UUID.randomUUID().toString();
		Map<String, String> roboticsFields = roboticsResearchMdcService.createContext(jobName, runId);
		Map<String, String> automotiveFields = automotiveEngineeringMdcService.createContext(jobName, runId);
		MDC.put("traceId", runId);
		MDC.put("jobName", jobName);
		MDC.put("jobRole", jobRole);
		MDC.put("framework", framework);
		MDC.put("appVariant", "with-mdc");
		MDC.put("mdcSample", "enabled");
		putAll(roboticsFields);
		putAll(automotiveFields);
		MDC.put("success", "true");
		MDC.put("elapsed_ms", String.valueOf(resolveElapsedMs(jobName)));
		try {
			String message = String.format(
					"OS+MDC : Robotics %s and automotive %s workflows executed by spring maven with MDC [%s]",
					roboticsFields.get("processStage"),
					automotiveFields.get("vehicleTestStage"),
					runId);
			log.info(message);
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
			default -> 1500L;
		};
	}

}
