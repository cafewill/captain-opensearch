# REVIEW2.md — 파라미터 및 기능 비교

`lib/logback-elasticsearch-appender-3.0.19` vs `lib/simple-lib-spring-opensearch-appender-3.0.0`

> 개선 작업(PR #20) 반영 후 기준. 3.0.0 은 logback 파라미터 이름을 완전히 정렬하여 향후 오픈소스 전환 시 logback-spring.xml 설정 변경 없이 라이브러리만 교체 가능.

---

## 1. 설정 파라미터 전체 대조표

| 파라미터 | logback 기본값 | 3.0.0 기본값 | 비고 |
|---|---|---|---|
| `url` | — (필수) | — (필수) | logback: `URL` 타입 / 3.0.0: `String` |
| `index` | — (필수) | — (필수) | `{date}` / `%date{yyyy.MM.dd}` 패턴 지원 |
| `type` | `null` | `null` | Bulk 액션 `_type` 필드. ES 8+ / OS 2+ 사용 안 함 |
| `sleepTime` | 250 ms | 250 ms | 백그라운드 스레드 폴링 간격 |
| `maxRetries` | 3 | 3 | 전송 실패 시 최대 재시도 횟수 |
| `connectTimeout` | 30000 ms | 30000 ms | HTTP 연결 타임아웃 |
| `readTimeout` | 30000 ms | 30000 ms | HTTP 읽기 타임아웃 |
| `maxQueueSize` | 100 MB (bytes) | 100 MB (bytes) | logback: 메모리 바이트 기준 / 3.0.0: `capacity = maxQueueSize / 512` 로 아이템 수 환산 |
| `maxBatchSize` | -1 (무제한) | -1 (무제한) | 배치당 최대 이벤트 수 |
| `maxMessageSize` | -1 (무제한) | -1 (무제한) | 메시지 최대 길이(바이트). 초과 시 `..` 접미 잘림 |
| `operation` | `create` | `create` | index / create / update / delete |
| `authentication` | `null` | `null` | 인터페이스 주입 (BasicAuthentication / AWSAuthentication) |
| `includeMdc` | `false` | `true` | MDC 필드 문서 포함 여부 |
| `includeKvp` | `false` | `false` | SLF4J Key-Value Pair 포함 여부 |
| `includeCallerData` | `false` | `false` | 호출자 클래스/메서드/파일/라인 포함 여부 |
| `rawJsonMessage` | `false` | `false` | 메시지를 JSON 객체로 파싱하여 포함 |
| `logsToStderr` | `false` | `false` | 전송 페이로드를 stderr 에 미러링 |
| `errorsToStderr` | `false` | `false` | 전송 오류를 stderr 에 출력 |
| `loggerName` | `null` | `null` | 전송 페이로드를 지정 SLF4J 로거로 미러링 |
| `errorLoggerName` | `null` | `null` | 전송 오류를 지정 SLF4J 로거로 출력 |
| `timestampFormat` | `null` (ISO-8601) | `null` (ISO-8601) | `long` 지정 시 epoch ms. 커스텀 패턴 가능 |
| `keyPrefix` | `null` | `""` | StructuredArgs 필드명 앞에 붙는 접두사 |
| `objectSerialization` | `false` | `false` | Jackson 모듈 자동 등록 후 객체 직렬화 |
| `autoStackTraceLevel` | `OFF` | `OFF` | 이 레벨 이상 로그에 예외 없어도 스택트레이스 자동 첨부 |
| `headers` | — | — | 커스텀 HTTP 헤더 목록 |
| `properties` | — | — | PatternLayout 기반 고정 문서 필드 |
| `trustAllSsl` | ❌ 없음 | `true` | **3.0.0 전용 추가 파라미터.** 자가 서명 인증서 허용 |
| `includeStructuredArgs` | ❌ 없음 | `false` | **3.0.0 전용.** StructuredArgsOpenSearchAppender 전용 플래그 |

---

## 2. 인증 (Authentication)

| 항목 | logback | 3.0.0 |
|---|---|---|
| 인터페이스 | `Authentication` | `OpenSearchAuthentication` |
| Basic 인증 | `BasicAuthentication` (username/password + URL userInfo 폴백) | `OpenSearchBasicAuthentication` (동일 방식) |
| AWS v4 서명 | `AWSAuthentication` (DefaultAWSCredentialsProviderChain) | ❌ 미구현 (필요 시 인터페이스 직접 구현) |
| 설정 방법 | `<authentication class="...BasicAuthentication">` | 동일 |

---

## 3. 문서 필드 구성

| 필드 | logback | 3.0.0 |
|---|---|---|
| `@timestamp` | ✅ ISO-8601 (JVM 기본 타임존) | ✅ ISO-8601 (JVM 기본 타임존) |
| `level` | ✅ | ✅ |
| `thread` | ✅ | ✅ |
| `logger` | ✅ | ✅ |
| `message` | ✅ | ✅ |
| `exception_class` | ✅ | ✅ |
| `exception_message` | ✅ | ✅ |
| `stack_trace` | ✅ | ✅ (Caused by 체인 포함) |
| `caller_class/method/file/line` | ✅ (`includeCallerData=true`) | ✅ (`includeCallerData=true`) |
| MDC 필드 | ✅ (`includeMdc=true`) | ✅ (`includeMdc=true`) |
| KVP 필드 | ✅ (`includeKvp=true`) | ✅ (`includeKvp=true`) |
| StructuredArgs 필드 | ✅ (StructuredArgsElasticsearchPublisher) | ✅ (StructuredArgsOpenSearchAppender) |
| 커스텀 properties | ✅ (PatternLayout) | ✅ (PatternLayout) |
| `app` / `env` / `instance_id` | ❌ 없음 | ❌ 제거됨 (PR #20) |

> MDC 코어션: 3.0.0 은 `http_status`, `duration_ms` 등 알려진 키를 숫자/불리언으로 자동 변환. logback 은 모두 문자열.

---

## 4. 큐 및 전송 아키텍처

| 항목 | logback | 3.0.0 |
|---|---|---|
| 큐 구현 | `ConcurrentLinkedQueue` (unbounded) | `ArrayBlockingQueue` (bounded, capacity = maxQueueSize / 512) |
| 큐 포화 처리 | 메모리 바이트 추정 초과 시 이벤트 드롭 | `queue.offer()` 실패 시 warn 로그 후 드롭 |
| 백그라운드 스레드 | `sleepTime` 간격 스핀 루프 | `BlockingQueue.poll(sleepTime)` 대기 |
| 벌크 페이로드 | JSON streaming (JsonGenerator) | Jackson ObjectNode → NDJSON 문자열 빌드 |
| 재시도 딜레이 | `sleepTime` 고정 | `sleepTime` 고정 |
| 재시도 항목 | 전체 재시도 | 응답 status 429/5xx 항목만 선별 재시도 |
| Dead Letter | ❌ 없음 | ❌ 제거됨 (PR #20). `errorLoggerName` / `errorsToStderr` 로 대체 |
| 셧다운 훅 | `Runtime.addShutdownHook` | `stop()` + `flushRemaining()` |

---

## 5. SSL/TLS

| 항목 | logback | 3.0.0 |
|---|---|---|
| trustAllSsl | ❌ 미지원 | ✅ `trustAllSsl=true` (HostnameVerifier 무효화 + X509TrustManager 전체 허용) |
| 용도 | — | 내부망 자가 서명 인증서 환경 |

---

## 6. Appender 클래스 구성

| 클래스 | logback | 3.0.0 |
|---|---|---|
| 표준 | `ElasticsearchAppender` | `OpenSearchAppender` |
| StructuredArgs | `StructuredArgsElasticsearchAppender` | `StructuredArgsOpenSearchAppender` |
| Access 로그 | `ElasticsearchAccessAppender` | ❌ 미구현 |
| Job / Web MDC | ❌ 없음 | ❌ 제거됨 (PR #20) |

---

## 7. 의존성

| 항목 | logback | 3.0.0 |
|---|---|---|
| logback-classic | provided | provided |
| slf4j-api | provided | provided |
| jackson-databind | ✅ | ✅ |
| logstash-logback-encoder | optional | optional |
| aws-java-sdk | optional (AWSAuthentication 용) | ❌ 미포함 |
| spring-boot / spring-context | ❌ 없음 | ❌ 제거됨 (PR #20) |

---

## 8. 향후 오픈소스 전환 시 체크리스트

향후 `logback-elasticsearch-appender` 가 `trustAllSsl` 를 공식 지원하면 아래만 확인 후 라이브러리 교체 가능.

| 항목 | 확인 사항 |
|---|---|
| `logback-spring.xml` | 파라미터 이름 동일 → 변경 불필요 |
| `authentication` 클래스 | `com.cube.opensearch.OpenSearchBasicAuthentication` → `com.agido.logback.elasticsearch.config.BasicAuthentication` 으로 교체 |
| `trustAllSsl` | 오픈소스 지원 여부 확인 후 제거 또는 유지 |
| `includeStructuredArgs` | 오픈소스는 `StructuredArgsElasticsearchAppender` 사용으로 자동 처리 |
| MDC 코어션 (숫자/불리언 변환) | 오픈소스는 모두 문자열 → 인덱스 매핑 확인 필요 |
| `includeMdc` 기본값 | logback 기본 `false` / 3.0.0 기본 `true` → 명시적 설정 권장 |
| `operation` 기본값 | 동일 (`create`) → 변경 불필요 |
