# Library Review: logback-elasticsearch-appender-3.0.19 vs simple-lib-spring-opensearch-appender-3.0.0

비교 기준일: 2026-04-25

---

## 1. 개요

| 항목 | logback-elasticsearch-appender-3.0.19 | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 패키지 | `com.agido.logback.elasticsearch.*` | `com.cube.opensearch.*` |
| 대상 서버 | Elasticsearch (OpenSearch 호환) | OpenSearch (Elasticsearch 호환) |
| Java 버전 | 8+ | 21 |
| Spring Boot 의존 | 없음 (순수 Logback 라이브러리) | Spring Boot 3.5 (AutoConfiguration 포함) |
| 인증 방식 | BasicAuthentication, AWSAuthentication | username / password (인라인) |
| SSL 설정 | 미지원 (JVM 기본 TrustStore 사용) | `trustAllSsl=true` 지원 |
| Dead Letter | 미지원 | Slf4j Dead Letter Logger 지원 |
| Spring AutoConfiguration | 미지원 | MdcJobFilter / MdcWebFilter 자동 등록 |

---

## 2. 아키텍처 비교

### 2-1. 큐 / 스레드 구조

| 항목 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 큐 타입 | `ConcurrentLinkedQueue` (무제한) | `ArrayBlockingQueue` (bounded, `queueSize` 설정) |
| 큐 한도 초과 시 | `maxQueueSize` 초과 시 경고 후 손실 | offer 실패 시 warn 후 손실 |
| 스레드 수 | 이벤트 도착 시 매번 새 스레드 생성 | 고정 1개 writer 스레드 (데몬) |
| 플러시 트리거 | `sleepTime`(ms) 대기 후 드레인 | 시간(`flushIntervalSeconds`) / 바이트(`maxBatchBytes`) / 건수(`maxBatchSize`) 3중 트리거 |

### 2-2. 재시도 정책

| 항목 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 재시도 방식 | 단순 횟수 카운터 (`maxRetries`) | 지수 백오프 (`retryInitialDelayMillis` × 2^n, 최대 `retryMaxDelayMillis`) |
| 부분 실패 처리 | 전체 재시도 | 아이템별 retryable(429/5xx) vs fatal(4xx) 분류 후 retryable만 재시도 |
| Dead Letter | 없음 | `deadLetterLoggerName` 로거에 실패 아이템 기록 |

### 2-3. 벌크 응답 분석

| 항목 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| errors:false | 성공 처리 | 성공 처리 |
| errors:true | 전체 재시도 | 아이템별 status 파싱 → retryable / fatal 분리 |
| `retryPartialFailures=false` | 미지원 | fatal 처리 후 dead letter 저장 |

---

## 3. 설정 파라미터 전체 비교

### 3-1. 연결 / 인증

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 엔드포인트 URL | `url` (java.net.URL) | `url` (String) |
| Basic 인증 | `<authentication class="...BasicAuthentication">` 태그 또는 URL userinfo | `username` / `password` 인라인 |
| AWS 인증 | `<authentication class="...AWSAuthentication">` (aws-sdk 필요) | 미지원 |
| SSL 전체 허용 | 미지원 | `trustAllSsl` (기본값 `true`) |
| 커스텀 헤더 | `<headers><header>` | `<headers><header>` |
| GZIP 전송 | `Content-Encoding: gzip` 헤더 추가 시 자동 압축 | 동일 (커스텀 헤더로 가능) |
| 연결 타임아웃 | `connectTimeout` (ms, 기본 30000) | `connectTimeoutMillis` (ms, 기본 3000) |
| 읽기 타임아웃 | `readTimeout` (ms, 기본 30000) | `readTimeoutMillis` (ms, 기본 5000) |

### 3-2. 인덱스 / 문서

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 인덱스 | `index` (PatternLayout 표현식) | `index` (`{date}` 또는 `%date{yyyy.MM.dd}` 치환) |
| `_type` 필드 | `type` (deprecated) | 미지원 |
| 벌크 작업 | `operation` (index / create / update / delete) | `operation` (index / create) |
| 타임스탬프 포맷 | `timestampFormat` (`long` 또는 SimpleDateFormat 패턴) | `timestampFormat` (`long` 또는 DateTimeFormatter 패턴) |
| 타임스탬프 존 | 미지원 (JVM 기본 시간대) | `timestampZone` (기본 UTC) |

### 3-3. 배치 / 큐 / 플러시

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 배치 건수 제한 | `maxBatchSize` (기본 -1, 무제한) | `maxBatchSize` (기본 200) |
| 배치 바이트 제한 | 미지원 | `maxBatchBytes` (기본 256KB) |
| 큐 최대 바이트 | `maxQueueSize` (기본 100MB) | 미지원 |
| 큐 최대 건수 | 미지원 | `queueSize` (기본 10,000건) |
| 플러시 주기 | `sleepTime` (ms, 기본 250) | `flushIntervalSeconds` (초, 기본 3) |
| 메시지 최대 크기 | `maxMessageSize` (기본 -1, 무제한) | `maxMessageSize` (기본 32KB) |

### 3-4. 재시도

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 최대 재시도 횟수 | `maxRetries` (기본 3) | `maxRetries` (기본 3) |
| 초기 재시도 지연 | 미지원 (즉시 재시도) | `retryInitialDelayMillis` (기본 500ms) |
| 최대 재시도 지연 | 미지원 | `retryMaxDelayMillis` (기본 10,000ms) |
| 부분 실패 재시도 | 미지원 | `retryPartialFailures` (기본 true) |
| Dead Letter 로거 | 미지원 | `deadLetterLoggerName` |

### 3-5. 로그 내용 제어

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| MDC 포함 | `includeMdc` (기본 false) | `includeMdc` (기본 true) |
| KVP 포함 | `includeKvp` (기본 false) | `includeKvp` (기본 false) |
| Caller Data 포함 | `includeCallerData` (기본 false) | `includeCallerData` (기본 false) |
| Raw JSON 메시지 | `rawJsonMessage` (기본 false) | `rawJsonMessage` (기본 false) |
| MDC 타입 자동 변환 | 미지원 (문자열 고정) | 지원 (`elapsed_ms`→Long, `success`→Boolean 등) |
| 자동 스택트레이스 | `autoStackTraceLevel` (기본 OFF) | `autoStackTraceLevel` (기본 OFF) ✅ 3.0.0 추가 |

### 3-6. 커스텀 프로퍼티

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 커스텀 프로퍼티 | `<properties><property>` (PatternLayout 표현식) | `<properties><property>` (PatternLayout 표현식) |
| 프로퍼티 타입 | STRING / INT / FLOAT / BOOLEAN | STRING / INT / FLOAT / BOOLEAN |
| allowEmpty | 지원 | 지원 |
| keyPrefix | `keyPrefix` (StructuredArgs 전용) | `keyPrefix` ✅ 3.0.0 추가 |
| objectSerialization | `objectSerialization` (Jackson 모듈 등록) | `objectSerialization` ✅ 3.0.0 추가 |

### 3-7. StructuredArgs

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| StructuredArgs 지원 | `StructuredArgsElasticsearchAppender` | `StructuredArgsOpenSearchAppender` ✅ 3.0.0 추가 |
| 구현 방식 | getDeclaredField 리플렉션 | getDeclaredField 리플렉션 (동일) |
| 런타임 옵션 여부 | 클래스 로딩 실패 시 오류 | 클래스패스 없어도 무시 후 정상 동작 |
| `includeStructuredArgs` 플래그 | 미지원 (Appender 교체 필수) | `includeStructuredArgs` (기본 Appender에서도 활성화 가능) |

### 3-8. 로그 미러링 / 에러 보고

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 페이로드 stderr 출력 | `logsToStderr` | `logsToStderr` |
| 에러 stderr 출력 | `errorsToStderr` | `errorsToStderr` |
| 페이로드 로거 미러링 | `loggerName` | `loggerName` |
| 에러 로거 미러링 | `errorLoggerName` | `errorLoggerName` |

### 3-9. 식별 정보 (3.0.0 전용)

| 파라미터 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 앱 이름 | 미지원 | `app` (문서 고정 필드 `app`) |
| 환경 | 미지원 | `env` (문서 고정 필드 `env`) |
| 인스턴스 ID | 미지원 | `instanceId` (자동 감지: HOSTNAME > InetAddress > PID) |

---

## 4. Appender 종류 비교

| Appender | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| 기본 (ILoggingEvent) | `ElasticsearchAppender` | `OpenSearchAppender` |
| Access (IAccessEvent) | `ElasticsearchAccessAppender` | 미지원 |
| StructuredArgs | `StructuredArgsElasticsearchAppender` | `StructuredArgsOpenSearchAppender` ✅ |
| Job 전용 | 미지원 | `OpenSearchJobAppender` (deprecated, MdcJobFilter 사용 권장) |
| Web 전용 | 미지원 | `OpenSearchWebAppender` (deprecated, MdcWebFilter 사용 권장) |

---

## 5. Spring Boot 통합 기능 (3.0.0 전용)

| 기능 | 설명 |
|---|---|
| `MdcJobFilter` | `@Scheduled` 작업에 `app`, `env`, `instance_id`, `job_name`, `trigger_time` MDC 자동 주입 |
| `MdcWebFilter` | HTTP 요청마다 `trace_id`, `http_method`, `http_path`, `client_ip`, `http_status`, `duration_ms` MDC 자동 주입 |
| `OpenSearchJobAutoConfiguration` | `cube.opensearch.job.enabled=true`(기본)일 때 MdcJobFilter + TaskScheduler 자동 등록 |
| `OpenSearchWebAutoConfiguration` | `cube.opensearch.web.enabled=true`(기본)일 때 MdcWebFilter 자동 등록 |

---

## 6. 문서 필드 구조 비교

### logback-elasticsearch-appender 문서 필드

| 필드 | 설명 |
|---|---|
| `@timestamp` | 로그 타임스탬프 |
| `message` | 로그 메시지 |
| MDC 키/값 | `includeMdc=true` 시 (문자열 고정) |
| KVP 키/값 | `includeKvp=true` 시 |
| StructuredArgs 키/값 | `StructuredArgsElasticsearchAppender` 사용 시 |
| `<property>` 정의 필드 | PatternLayout 표현식으로 커스텀 필드 |

### simple-lib-spring-opensearch-appender-3.0.0 문서 필드

| 필드 | 설명 |
|---|---|
| `@timestamp` | 로그 타임스탬프 |
| `app` | 앱 이름 |
| `env` | 환경 |
| `instance_id` / `host` | 인스턴스 식별자 |
| `level` | 로그 레벨 |
| `thread` | 스레드명 |
| `logger` | 로거명 |
| `message` | 로그 메시지 |
| `exception_class` | 예외 클래스명 |
| `exception_message` | 예외 메시지 |
| `stack_trace` | 스택트레이스 전문 |
| `caller_class/method/file/line` | `includeCallerData=true` 시 |
| MDC 키/값 | `includeMdc=true` 시 (숫자/불리언 자동 변환) |
| KVP 키/값 | `includeKvp=true` 시 |
| StructuredArgs 키/값 | `includeStructuredArgs=true` 또는 `StructuredArgsOpenSearchAppender` 사용 시 |
| `<property>` 정의 필드 | PatternLayout 표현식으로 커스텀 필드 |

---

## 7. 주요 기능 차이 요약

| 기능 | logback-elasticsearch-appender | 3.0.0 | 비고 |
|---|---|:---:|---|
| trustAllSsl | ❌ | ✅ | 내부망 자가 서명 인증서 지원 |
| 지수 백오프 재시도 | ❌ | ✅ | 과부하 상황에서 안전 |
| 아이템별 retryable 분석 | ❌ | ✅ | partial failure 대응 |
| Dead Letter 로거 | ❌ | ✅ | 유실 이벤트 추적 가능 |
| 바이트 기반 배치 플러시 | ❌ | ✅ | 안정적인 페이로드 크기 제어 |
| MDC 타입 자동 변환 | ❌ | ✅ | Dashboards 숫자/불리언 집계 정확도 향상 |
| app / env / instance_id | ❌ | ✅ | 멀티 앱 환경 구분 |
| Spring AutoConfiguration | ❌ | ✅ | MdcJobFilter / MdcWebFilter |
| AWSAuthentication | ✅ | ❌ | AWS OpenSearch Service 사용 시 필요 |
| logback-access 지원 | ✅ | ❌ | HTTP Access 로그 별도 수집 시 필요 |
| `_type` 필드 | ✅ | ❌ | 구형 ES 7 이하 호환 시 필요 |
| autoStackTraceLevel | ✅ | ✅ | 3.0.0에서 동일 기능 구현 |
| objectSerialization | ✅ | ✅ | 3.0.0에서 동일 기능 구현 |
| keyPrefix | ✅ | ✅ | 3.0.0에서 동일 기능 구현 |
| StructuredArgsAppender | ✅ | ✅ | 3.0.0에서 동일 기능 구현 |
