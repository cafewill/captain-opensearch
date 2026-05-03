package com.cube.simple.service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${opensearch.name:app-default}")
    private String appName;

    // ════════════════════════════════════════════════════════════════════════
    // 기본 스케줄 잡
    // ════════════════════════════════════════════════════════════════════════

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    @Scheduled(fixedDelayString = "${job.system.delay:3000}")
    public void doSystemJob() {
        String runId = UUID.randomUUID().toString();
        String message = String.format("OS : Just do system job by [%s] [%s]", appName, runId);
        log.info(message);
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    @Scheduled(fixedDelayString = "${job.manager.delay:15000}")
    public void doManagerJob() {
        String runId = UUID.randomUUID().toString();
        String message = String.format("OS : Just do manager job by [%s] [%s]", appName, runId);
        log.info(message);
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    @Scheduled(fixedDelayString = "${job.operator.delay:20000}")
    public void doOperatorJob() {
        String runId = UUID.randomUUID().toString();
        String message = String.format("OS : Just do operator job by [%s] [%s]", appName, runId);
        log.info(message);
    }

    @Scheduled(fixedRateString = "${job.risky.delay:60000}")
    public void doRiskyJob() throws InterruptedException {
        String runId = UUID.randomUUID().toString();
        int chance = ThreadLocalRandom.current().nextInt(100);

        if (chance < 80) {
            String message = String.format("OS : Risky job by [%s] completed normally [%s]", appName, runId);
            log.info(message);
            return;
        }

        Thread.sleep(ThreadLocalRandom.current().nextLong(3000, 10001));

        String message = String.format("OS : Risky job by [%s] found unstable condition [%s]", appName, runId);

        if (ThreadLocalRandom.current().nextBoolean()) {
            log.warn(message);
        } else {
            log.error(message);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Vision AI 파이프라인 MDC 모니터링 예제
    // ════════════════════════════════════════════════════════════════════════

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    @Scheduled(fixedDelayString = "${job.vision.classification.delay:5000}")
    public void doImageClassificationJob() throws InterruptedException {
        String jobId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        MDC.put("visionJobId", jobId);
        MDC.put("pipeline", "image-classification");
        MDC.put("model", "resnet-50");
        MDC.put("inputType", "IMAGE");
        MDC.put("stage", "INFERENCE");

        try {
            int itemCount = ThreadLocalRandom.current().nextInt(1, 11);
            Thread.sleep(ThreadLocalRandom.current().nextLong(30, 250));

            double confidence = 0.70 + ThreadLocalRandom.current().nextDouble(0.29);

            MDC.put("confidence", String.format("%.3f", confidence));
            MDC.put("itemCount", String.valueOf(itemCount));
            MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
            MDC.put("status", "SUCCESS");

            String message = String.format(
                "OS : Image classification job by [%s] completed - items=[%d] [%s]",
                appName,
                itemCount,
                jobId
            );
            log.info(message);
        } finally {
            MDC.clear();
        }
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    @Scheduled(fixedDelayString = "${job.vision.detection.delay:18000}")
    public void doObjectDetectionJob() throws InterruptedException {
        String jobId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        MDC.put("visionJobId", jobId);
        MDC.put("pipeline", "object-detection");
        MDC.put("model", "yolo-v8");
        MDC.put("inputType", "VIDEO_FRAME");
        MDC.put("stage", "INFERENCE");

        try {
            int detectedObjects = ThreadLocalRandom.current().nextInt(0, 16);
            Thread.sleep(ThreadLocalRandom.current().nextLong(80, 500));

            double confidence = 0.60 + ThreadLocalRandom.current().nextDouble(0.39);

            MDC.put("confidence", String.format("%.3f", confidence));
            MDC.put("itemCount", String.valueOf(detectedObjects));
            MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
            MDC.put("status", "SUCCESS");

            String message = String.format(
                "OS : Object detection job by [%s] completed - objects=[%d] [%s]",
                appName,
                detectedObjects,
                jobId
            );
            log.info(message);
        } finally {
            MDC.clear();
        }
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    @Scheduled(fixedDelayString = "${job.vision.ocr.delay:25000}")
    public void doOcrJob() throws InterruptedException {
        String jobId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        MDC.put("visionJobId", jobId);
        MDC.put("pipeline", "ocr");
        MDC.put("model", "tesseract-ocr");
        MDC.put("inputType", "DOCUMENT");
        MDC.put("stage", "POSTPROCESS");

        try {
            int pageCount = ThreadLocalRandom.current().nextInt(1, 21);
            Thread.sleep(ThreadLocalRandom.current().nextLong(150, 700));

            double confidence = 0.82 + ThreadLocalRandom.current().nextDouble(0.17);

            MDC.put("confidence", String.format("%.3f", confidence));
            MDC.put("itemCount", String.valueOf(pageCount));
            MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
            MDC.put("status", "SUCCESS");

            String message = String.format(
                "OS : OCR job by [%s] completed - pages=[%d] [%s]",
                appName,
                pageCount,
                jobId
            );
            log.info(message);
        } finally {
            MDC.clear();
        }
    }

    @Scheduled(fixedRateString = "${job.vision.batch.delay:90000}")
    public void doBatchVideoAnalysisJob() throws InterruptedException {
        String jobId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        MDC.put("visionJobId", jobId);
        MDC.put("pipeline", "video-analysis");
        MDC.put("model", "clip-vit-l14");
        MDC.put("inputType", "VIDEO");

        try {
            int batchSize = ThreadLocalRandom.current().nextInt(5, 51);
            int chance = ThreadLocalRandom.current().nextInt(100);

            MDC.put("itemCount", String.valueOf(batchSize));

            if (chance < 75) {
                MDC.put("stage", "INFERENCE");
                Thread.sleep(ThreadLocalRandom.current().nextLong(500, 2000));

                double confidence = 0.72 + ThreadLocalRandom.current().nextDouble(0.27);

                MDC.put("confidence", String.format("%.3f", confidence));
                MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
                MDC.put("status", "SUCCESS");

                String message = String.format(
                    "OS : Batch video analysis job by [%s] completed - batch=[%d] [%s]",
                    appName,
                    batchSize,
                    jobId
                );
                log.info(message);

            } else if (chance < 90) {
                MDC.put("stage", "PREPROCESS");
                Thread.sleep(ThreadLocalRandom.current().nextLong(3000, 8000));

                MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
                MDC.put("status", "PARTIAL");

                String message = String.format(
                    "OS : Batch video analysis job by [%s] found partial failure - batch=[%d], slow inference [%s]",
                    appName,
                    batchSize,
                    jobId
                );
                log.warn(message);

            } else {
                MDC.put("stage", "PREPROCESS");
                MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
                MDC.put("status", "FAILED");

                String message = String.format(
                    "OS : Batch video analysis job by [%s] failed - batch=[%d], model inference error [%s]",
                    appName,
                    batchSize,
                    jobId
                );
                log.error(message);
            }
        } finally {
            MDC.clear();
        }
    }

    @Recover
    public void recoverJob(Exception e) {
        String message = String.format(
            "OS : scheduled job by [%s] failed after retries - %s",
            appName,
            e.getMessage()
        );
        log.error(message, e);
    }
}