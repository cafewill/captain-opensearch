package com.cube.simple.service;

import java.util.UUID;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ScheduleService {

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.system.delay:3000}")
	public void doSystemJob() {
		String message = String.format("OS : Just do system job by spring maven [%s]", UUID.randomUUID());
		log.info(message);
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.manager.delay:15000}")
	public void doManagerJob() {
		String message = String.format("OS : Just do manager job by spring maven [%s]", UUID.randomUUID());
		log.info(message);
	}

	@Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
	@Scheduled(fixedDelayString = "${job.operator.delay:20000}")
	public void doOperatorJob() {
		String message = String.format("OS : Just do operator job by spring maven [%s]", UUID.randomUUID());
		log.info(message);
	}

	@Recover
	public void recoverJob(Exception e) {
		log.error("OS : scheduled job failed after retries - {}", e.getMessage(), e);
	}
}
