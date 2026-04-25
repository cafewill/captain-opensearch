package com.cube.simple.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class BatchSchedulerTrackingMdcService {
	private static final Map<String, BatchJobProfile> JOB_PROFILES = Map.of(
			"system", new BatchJobProfile(
					"system-health-sweep",
					"platform-maintenance",
					"cron",
					"worker-a1",
					"00:00-00:05",
					"system-checkpoint"),
			"manager", new BatchJobProfile(
					"manager-rollup",
					"control-plane",
					"fixed-delay",
					"worker-b2",
					"00:05-00:20",
					"manager-checkpoint"),
			"operator", new BatchJobProfile(
					"operator-sync",
					"runtime-ops",
					"fixed-delay",
					"worker-c3",
					"00:20-00:40",
					"operator-checkpoint"),
			"risky", new BatchJobProfile(
					"risky-integrity-check",
					"risk-control",
					"fixed-rate",
					"worker-r9",
					"00:40-01:00",
					"risky-checkpoint"),
			"slow", new BatchJobProfile(
					"slow-method-watch",
					"performance-watch",
					"fixed-rate",
					"worker-p5",
					"01:00-01:05",
					"slow-method-checkpoint"));

	public Map<String, String> createContext(String jobName, String runId, String status, long elapsedMs) {
		BatchJobProfile profile = JOB_PROFILES.getOrDefault(jobName, BatchJobProfile.defaultProfile());
		return Map.ofEntries(
				Map.entry("observabilityUseCase", "batch-scheduler-tracking"),
				Map.entry("schedulerName", "spring-scheduler"),
				Map.entry("jobName", jobName),
				Map.entry("jobGroup", profile.jobGroup()),
				Map.entry("jobRole", profile.jobGroup()),
				Map.entry("jobExecutionId", "job-" + runId.substring(0, 8)),
				Map.entry("triggerType", profile.triggerType()),
				Map.entry("workerNode", profile.workerNode()),
				Map.entry("batchWindow", profile.batchWindow()),
				Map.entry("checkpointName", profile.checkpointName()),
				Map.entry("scheduledAt", Instant.now().toString()),
				Map.entry("runStatus", status),
				Map.entry("retryAttempt", "0"),
				Map.entry("elapsed_ms", String.valueOf(elapsedMs)));
	}

	private record BatchJobProfile(
			String jobName,
			String jobGroup,
			String triggerType,
			String workerNode,
			String batchWindow,
			String checkpointName) {
		private static BatchJobProfile defaultProfile() {
			return new BatchJobProfile(
					"generic-batch",
					"batch",
					"manual",
					"worker-z0",
					"ad-hoc",
					"default-checkpoint");
		}
	}
}
