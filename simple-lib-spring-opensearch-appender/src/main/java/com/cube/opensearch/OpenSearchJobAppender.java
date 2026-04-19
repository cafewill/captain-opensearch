package com.cube.opensearch;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.net.ssl.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 추가 의존성 없이 JDK + logback-classic 만으로 구현한 OpenSearch 어펜더.
 *
 * [라이브러리 사용 시]
 *   1. pom.xml / build.gradle 에 simple-lib-spring-opensearch-appender 의존성 추가
 *   2. logback-spring.xml 에 appender class="com.cube.opensearch.OpenSearchJobAppender" 추가
 *   3. application.properties 에 opensearch.* 프로퍼티 추가
 *   → MdcJobFilter 는 OpenSearchJobAutoConfiguration 이 자동 등록 (별도 설정 불필요)
 *
 * [단독 파일 복사 사용 시]
 *   1. {base-package}/config/ 에 복사 (패키지 선언만 수정)
 *   2. logback-spring.xml 에 appender 설정 추가
 *   3. application.properties 에 opensearch.* 프로퍼티 추가
 *   → MdcJobFilter 는 컴포넌트 스캔이 자동 감지
 *
 * 포함된 기능:
 *   [Appender]     _bulk API 배치 전송 / HTTPS TrustAll (내부망) / 스택 트레이스 / MDC 자동 포함
 *   [MdcJobFilter] 스케줄러 스레드마다 app · env · instance_id 를 MDC 에 자동 설정
 */
public class OpenSearchJobAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    // ── logback XML 바인딩 프로퍼티 ───────────────────────────────────────────

    private String url;
    private String index;
    private String username;
    private String password;
    private String app;
    private String env = "local";

    private int maxBatchBytes        = 1_000_000;
    private int flushIntervalSeconds = 1;
    private int queueSize            = 8192;

    // ── 런타임 상태 ───────────────────────────────────────────────────────────

    private String           instanceId;
    private String           basicAuth;
    private SSLSocketFactory sslSocketFactory;
    private BlockingQueue<ILoggingEvent> queue;
    private Thread           writerThread;

    // ── 상수 ─────────────────────────────────────────────────────────────────

    private static final HostnameVerifier  NOOP_VERIFIER = (h, s) -> true;
    private static final DateTimeFormatter DATE_IDX      =
        DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter DATE_TS       =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter DATE_TS_LOCAL =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TS_KST   =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Seoul"));

    private static final Set<String> NUMERIC_MDC_FIELDS = Set.of("http_status", "duration_ms");
    // toBulkLine() 에서 고정 필드로 이미 추가되는 키 — MDC 루프에서 중복 방지
    private static final Set<String> FIXED_DOC_FIELDS  = Set.of("app", "env", "instance_id");

    // ── 생명주기 ─────────────────────────────────────────────────────────────

    @Override
    public void start() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, null);
            sslSocketFactory = sc.getSocketFactory();
        } catch (Exception e) {
            addError("TLS 초기화 실패", e);
            return;
        }

        String envHostname = System.getenv("HOSTNAME");
        if (envHostname != null && !envHostname.isBlank()) {
            instanceId = envHostname;
        } else {
            try {
                instanceId = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                instanceId = "unknown-" + ProcessHandle.current().pid();
            }
        }

        if (username != null && !username.isEmpty()) {
            basicAuth = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        queue        = new LinkedBlockingQueue<>(queueSize);
        writerThread = new Thread(this::run, "opensearch-log-sender");
        writerThread.setDaemon(true);
        writerThread.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (writerThread != null) writerThread.interrupt();
        if (queue != null && !queue.isEmpty()) {
            StringBuilder bulk = new StringBuilder();
            ILoggingEvent event;
            while ((event = queue.poll()) != null) bulk.append(toBulkLine(event));
            if (bulk.length() > 0) send(bulk.toString());
        }
    }

    // ── 로그 수신 ─────────────────────────────────────────────────────────────

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        if (!queue.offer(event)) {
            addWarn("OpenSearch 큐 포화 — 로그 드롭: " + event.getFormattedMessage());
        }
    }

    // ── 배치 전송 루프 ────────────────────────────────────────────────────────

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ILoggingEvent head = queue.poll(flushIntervalSeconds, TimeUnit.SECONDS);
                if (head == null) continue;

                StringBuilder bulk = new StringBuilder();
                bulk.append(toBulkLine(head));
                while (bulk.length() < maxBatchBytes) {
                    ILoggingEvent next = queue.poll();
                    if (next == null) break;
                    bulk.append(toBulkLine(next));
                }

                send(bulk.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                addError("OpenSearch 전송 스레드 예외: " + e.getMessage(), e);
            }
        }
    }

    // ── JSON / Bulk 직렬화 ────────────────────────────────────────────────────

    private String toBulkLine(ILoggingEvent e) {
        Instant instant = Instant.ofEpochMilli(e.getTimeStamp());
        String today   = DATE_IDX.format(instant);
        String idxName = index.replace("%date{yyyy.MM.dd}", today);
        String ts      = DATE_TS.format(instant);
        String tsLocal = DATE_TS_LOCAL.format(instant);
        String tsKst   = DATE_TS_KST.format(instant);

        StringBuilder doc = new StringBuilder()
            .append("{\"index\":{\"_index\":\"").append(idxName).append("\"}}\n")
            .append("{")
            .append("\"@timestamp\":\"")       .append(ts)      .append('"')
            .append(",\"@timestamp_local\":\"").append(tsLocal)  .append('"')
            .append(",\"@timestamp_kst\":\"")  .append(tsKst)    .append('"')
            .append(",\"level\":\"")       .append(esc(e.getLevel().toString())) .append('"')
            .append(",\"message\":\"")     .append(esc(e.getFormattedMessage())) .append('"')
            .append(",\"logger_name\":\"") .append(esc(e.getLoggerName()))       .append('"')
            .append(",\"thread_name\":\"") .append(esc(e.getThreadName()))       .append('"')
            .append(",\"app\":\"")         .append(esc(app != null ? app : ""))  .append('"')
            .append(",\"env\":\"")         .append(esc(env))                     .append('"')
            .append(",\"instance_id\":\"") .append(esc(instanceId))              .append('"');

        Map<String, String> mdc = e.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                String k = entry.getKey();
                if (FIXED_DOC_FIELDS.contains(k)) continue;
                String v = entry.getValue();
                doc.append(",\"").append(esc(k)).append("\":");
                if (NUMERIC_MDC_FIELDS.contains(k) && isNumeric(v)) {
                    doc.append(v);
                } else {
                    doc.append('"').append(esc(v)).append('"');
                }
            }
        }

        IThrowableProxy tp = e.getThrowableProxy();
        if (tp != null) {
            doc.append(",\"stack_trace\":\"").append(esc(stackTrace(tp))).append('"');
        }

        return doc.append("}\n").toString();
    }

    private String stackTrace(IThrowableProxy tp) {
        StringBuilder sb = new StringBuilder().append(tp.getClassName());
        if (tp.getMessage() != null) sb.append(": ").append(tp.getMessage());
        sb.append('\n');
        for (StackTraceElementProxy s : tp.getStackTraceElementProxyArray()) {
            sb.append("\tat ").append(s.getSTEAsString()).append('\n');
        }
        if (tp.getCause() != null) {
            sb.append("Caused by: ").append(stackTrace(tp.getCause()));
        }
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try { Long.parseLong(s); return true; } catch (NumberFormatException e) { return false; }
    }

    // ── HTTP 전송 ─────────────────────────────────────────────────────────────

    private void send(String body) {
        try {
            URL endpoint = new URL(url);
            HttpURLConnection conn;
            if ("https".equals(endpoint.getProtocol())) {
                HttpsURLConnection https = (HttpsURLConnection) endpoint.openConnection();
                https.setSSLSocketFactory(sslSocketFactory);
                https.setHostnameVerifier(NOOP_VERIFIER);
                conn = https;
            } else {
                conn = (HttpURLConnection) endpoint.openConnection();
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            if (basicAuth != null) conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

            int status = conn.getResponseCode();
            // Always read response body — prevents dirty pooled connections on next send
            try (InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (is != null) {
                    String respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    if (status >= 400) {
                        addWarn("OpenSearch _bulk HTTP " + status + ": " +
                                respBody.substring(0, Math.min(500, respBody.length())));
                    } else if (respBody.contains("\"errors\":true")) {
                        addWarn("OpenSearch _bulk partial errors: " +
                                respBody.substring(0, Math.min(500, respBody.length())));
                    }
                }
            }
            conn.disconnect();

        } catch (Exception e) {
            addError("OpenSearch 전송 실패: " + e.getMessage());
        }
    }

    // ── logback XML 프로퍼티 바인딩 ───────────────────────────────────────────

    public void setUrl(String url)                        { this.url = url; }
    public void setIndex(String index)                    { this.index = index; }
    public void setUsername(String username)              { this.username = username; }
    public void setPassword(String password)              { this.password = password; }
    public void setApp(String app)                        { this.app = app; }
    public void setEnv(String env)                        { this.env = env; }
    public void setMaxBatchBytes(int maxBatchBytes)       { this.maxBatchBytes = maxBatchBytes; }
    public void setFlushIntervalSeconds(int seconds)      { this.flushIntervalSeconds = seconds; }
    public void setQueueSize(int queueSize)               { this.queueSize = queueSize; }

    // =========================================================================
    // MdcJobFilter — 스케줄러 스레드 MDC 자동 설정
    //
    // [라이브러리 사용 시] OpenSearchJobAutoConfiguration 이 자동 등록 — 별도 사용 불필요.
    // [단독 파일 복사 시] Spring 컴포넌트 스캔이 static inner class 를 자동 감지.
    //
    // 설정되는 MDC 필드:
    //   app         — spring.application.name
    //   env         — opensearch.env (local | dev | staging | prod)
    //   instance_id — HOSTNAME 환경변수(k8s 파드명) or InetAddress
    // =========================================================================

    @Configuration
    public static class MdcJobFilter {

        @Value("${spring.application.name:app}")
        private String appName;

        @Value("${opensearch.env:local}")
        private String env;

        @Bean
        public TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(3);
            scheduler.setThreadNamePrefix("scheduled-");
            scheduler.setTaskDecorator(runnable -> () -> {
                try {
                    MDC.put("app",         appName);
                    MDC.put("env",         env);
                    MDC.put("instance_id", resolveInstanceId());
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            });
            return scheduler;
        }

        private String resolveInstanceId() {
            String hostname = System.getenv("HOSTNAME");
            if (hostname != null && !hostname.isBlank()) return hostname;
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                return "unknown-" + ProcessHandle.current().pid();
            }
        }
    }
}
