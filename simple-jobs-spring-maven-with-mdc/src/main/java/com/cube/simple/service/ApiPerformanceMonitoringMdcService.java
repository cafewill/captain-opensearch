package com.cube.simple.service;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ApiPerformanceMonitoringMdcService {
	private final int p95ThresholdMs;
	private final int p99ThresholdMs;
	private final int slowQueryThresholdMs;

	public ApiPerformanceMonitoringMdcService(
			@Value("${api.monitoring.p95-threshold-ms:800}") int p95ThresholdMs,
			@Value("${api.monitoring.p99-threshold-ms:1500}") int p99ThresholdMs,
			@Value("${api.monitoring.slow-query-threshold-ms:1200}") int slowQueryThresholdMs) {
		this.p95ThresholdMs = p95ThresholdMs;
		this.p99ThresholdMs = p99ThresholdMs;
		this.slowQueryThresholdMs = slowQueryThresholdMs;
	}

	public ApiPerformanceContext createContext(String scenarioName, String runId, String level) {
		int responseTime = resolveResponseTime(level);
		return createContext(scenarioName, runId, level, responseTime);
	}

	public ApiPerformanceContext createSlowMethodContext(String scenarioName, String runId, int responseTime) {
		String level = responseTime >= 1000 ? "WARN" : "INFO";
		return createContext(scenarioName, runId, level, responseTime);
	}

	private ApiPerformanceContext createContext(String scenarioName, String runId, String level, int responseTime) {
		boolean slowQuery = responseTime >= slowQueryThresholdMs;
		boolean slowMethod = responseTime >= 1000;
		String percentileBand = responseTime >= p99ThresholdMs ? "p99"
				: responseTime >= p95ThresholdMs ? "p95"
				: "normal";
		String latencyBucket = resolveLatencyBucket(responseTime);
		Map<String, String> fields = Map.ofEntries(
				Map.entry("observabilityUseCase", "api-performance-monitoring"),
				Map.entry("traceId", runId),
				Map.entry("apiScenario", scenarioName),
				Map.entry("httpMethod", "GET"),
				Map.entry("apiRoute", "/api/items/search"),
				Map.entry("queryName", "items-search-by-status"),
				Map.entry("responseTime", String.valueOf(responseTime)),
				Map.entry("responseTimeMs", String.valueOf(responseTime)),
				Map.entry("latencyBucket", latencyBucket),
				Map.entry("latencyHeatmapCell", percentileBand + ":" + latencyBucket),
				Map.entry("percentileTarget", percentileBand),
				Map.entry("p95ThresholdMs", String.valueOf(p95ThresholdMs)),
				Map.entry("p99ThresholdMs", String.valueOf(p99ThresholdMs)),
				Map.entry("slowQueryThresholdMs", String.valueOf(slowQueryThresholdMs)),
				Map.entry("slowQueryAlarm", String.valueOf(slowQuery)),
				Map.entry("slowQueryAlarmRule", "responseTime >= " + slowQueryThresholdMs + "ms"),
				Map.entry("slowMethodAlarm", String.valueOf(slowMethod)),
				Map.entry("slowMethodThresholdMs", "1000"),
				Map.entry("slowMethodAlarmRule", "responseTime >= 1000ms"),
				Map.entry("dbPoolName", "items-reader"),
				Map.entry("dashboardPanel", "api-latency-p95-p99-heatmap"));
		return new ApiPerformanceContext(fields, responseTime, percentileBand, slowQuery, slowMethod);
	}

	private int resolveResponseTime(String level) {
		return switch (level) {
			case "WARN" -> ThreadLocalRandom.current().nextInt(p95ThresholdMs, p99ThresholdMs);
			case "ERROR" -> ThreadLocalRandom.current().nextInt(p99ThresholdMs, 3201);
			default -> ThreadLocalRandom.current().nextInt(80, p95ThresholdMs);
		};
	}

	private String resolveLatencyBucket(int responseTime) {
		if (responseTime < 200) {
			return "000-199ms";
		}
		if (responseTime < 500) {
			return "200-499ms";
		}
		if (responseTime < p95ThresholdMs) {
			return "500-799ms";
		}
		if (responseTime < p99ThresholdMs) {
			return "800-1499ms";
		}
		return "1500ms-plus";
	}

	public record ApiPerformanceContext(
			Map<String, String> fields,
			int responseTime,
			String percentileBand,
			boolean slowQuery,
			boolean slowMethod) {
	}
}
