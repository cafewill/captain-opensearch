package com.cube.simple.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@ConditionalOnProperty(value = "job.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DemoScheduleService {

    // ════════════════════════════════════════════════════════════════════════
    // 기존 예제 (원본 유지)
    // ════════════════════════════════════════════════════════════════════════

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

    @Scheduled(fixedRateString = "${job.risky.delay:60000}")
    public void doRiskyJob() throws InterruptedException {
        String runId = UUID.randomUUID().toString();
        int chance = ThreadLocalRandom.current().nextInt(100);
        if (chance < 80) {
            log.info("OS : Risky job completed normally by spring maven [{}]", runId);
            return;
        }

        Thread.sleep(ThreadLocalRandom.current().nextLong(3000, 10001));
        String message = String.format("OS : Risky job found unstable condition by spring maven [%s]", runId);
        if (ThreadLocalRandom.current().nextBoolean()) {
            log.warn(message);
        } else {
            log.error(message);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Vision AI 파이프라인 MDC 모니터링 예제
    //
    // MDC 필드: visionJobId, pipeline, model, inputType, stage,
    //           confidence, itemCount, processingTimeMs, status
    //
    // 파이프라인:
    //   image-classification  (5s)  : 제품 이미지 분류   — resnet-50
    //   object-detection      (18s) : 영상 객체 탐지     — yolo-v8
    //   ocr                   (25s) : 문서 텍스트 추출   — tesseract-ocr
    //   video-analysis        (90s) : 배치 영상 분석     — clip-vit-l14 (실패 가능)
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
            log.info("[VisionAI] Image classification completed - {} items processed", itemCount);
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
            log.info("[VisionAI] Object detection completed - {} objects detected", detectedObjects);
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
            log.info("[VisionAI] OCR processing completed - {} pages extracted", pageCount);
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
            MDC.put("itemCount", String.valueOf(batchSize));
            int chance = ThreadLocalRandom.current().nextInt(100);

            if (chance < 75) {
                MDC.put("stage", "INFERENCE");
                Thread.sleep(ThreadLocalRandom.current().nextLong(500, 2000));
                double confidence = 0.72 + ThreadLocalRandom.current().nextDouble(0.27);
                MDC.put("confidence", String.format("%.3f", confidence));
                MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
                MDC.put("status", "SUCCESS");
                log.info("[VisionAI] Batch video analysis completed - batch={}", batchSize);
            } else if (chance < 90) {
                MDC.put("stage", "PREPROCESS");
                Thread.sleep(ThreadLocalRandom.current().nextLong(3000, 8000));
                MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
                MDC.put("status", "PARTIAL");
                log.warn("[VisionAI] Batch video analysis partial failure - batch={}, slow inference", batchSize);
            } else {
                MDC.put("stage", "PREPROCESS");
                MDC.put("processingTimeMs", String.valueOf(System.currentTimeMillis() - start));
                MDC.put("status", "FAILED");
                log.error("[VisionAI] Batch video analysis failed - batch={}, model inference error", batchSize);
            }
        } finally {
            MDC.clear();
        }
    }

    @Recover
    public void recoverJob(Exception e) {
        log.error("OS : scheduled job failed after retries - {}", e.getMessage(), e);
    }
}
