# REVIEW3.md — 기능 및 설정 파라미터 비교

비교 대상:

- `lib/logback-elasticsearch-appender-3.0.19`
- `lib/simple-lib-spring-opensearch-appender-3.0.0`

비교 기준일: 2026-04-25

> 기준은 각 라이브러리의 현재 로컬 소스, `pom.xml`, 예제 `logback-spring.xml`, README 이다. `simple-lib-spring-opensearch-appender-3.0.0`은 `logback-elasticsearch-appender`와 설정 이름을 최대한 맞추되 OpenSearch 내부망 운용을 위해 `trustAllSsl`과 partial failure 분석을 추가한 구현이다.

---

## 1. 요약 결론

| 항목 | 판단 |
|---|---|
| 일반 Logback 이벤트 전송 | 둘 다 지원 |
| OpenSearch 자가 서명 인증서 대응 | `simple-lib` 우위 (`trustAllSsl`) |
| Bulk 응답 partial failure 처리 | `simple-lib` 우위 (아이템별 429/5xx 재시도) |
| AWS OpenSearch Service 인증 | `logback-elasticsearch` 우위 (`AWSAuthentication`) |
| Logback Access 지원 | `logback-elasticsearch`만 지원 |
| 설정 호환성 | 상당히 높음. 단 인증 클래스명, `trustAllSsl`, `includeStructuredArgs`는 차이 있음 |
| Spring Boot 의존성 | 둘 다 없음. `simple-lib 3.0.0`도 순수 Logback 라이브러리 형태 |
| Java 버전 | `logback-elasticsearch`: Java 8 / `simple-lib`: Java 21 |

---

## 2. 라이브러리 개요

| 항목 | logback-elasticsearch-appender-3.0.19 | simple-lib-spring-opensearch-appender-3.0.0 |
|---|---|---|
| Maven 좌표 | `com.agido:logback-elasticsearch-appender:3.0.19` | `com.cube:simple-lib-spring-opensearch-appender:3.0.0` |
| 주요 패키지 | `com.agido.logback.elasticsearch` | `com.cube.opensearch` |
| 대상 | Elasticsearch bulk API | OpenSearch bulk API |
| Bulk URL 처리 | 설정한 `url` 그대로 사용 | `url` 끝에 `/_bulk`가 없으면 자동 추가 |
| Java | 8 | 21 |
| Logback | 1.3.14 provided | 1.5.18 provided |
| SLF4J | 2.0.5 provided | 2.0.17 provided |
| Jackson | `jackson-core 2.18.6`, `jackson-databind 2.14.1` | `jackson-databind 2.18.6` |
| logstash-logback-encoder | 7.0.1 compile | 8.0 provided optional |
| AWS SDK | `aws-java-sdk-core` provided | 없음 |

---

## 3. Appender 구성

| Appender 유형 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender |
|---|---|---|
| 표준 `ILoggingEvent` | `ElasticsearchAppender` | `OpenSearchAppender` |
| StructuredArgs 전용 | `StructuredArgsElasticsearchAppender` | `StructuredArgsOpenSearchAppender` |
| Logback Access `IAccessEvent` | `ElasticsearchAccessAppender` | 미지원 |
| 구 패키지 호환 | `com.internetitem.logback.elasticsearch.*` 래퍼 제공 | 미지원 |

---

## 4. 전송 아키텍처

| 항목 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender |
|---|---|---|
| 비동기 처리 | 이벤트 수신 시 writer thread 기동, 큐를 비울 때 종료 | appender 시작 시 daemon writer thread 1개 유지 |
| 이벤트 큐 | `ConcurrentLinkedQueue` | `ArrayBlockingQueue` |
| 큐 제한 방식 | 전송 문자열 버퍼 길이 기준 `maxQueueSize` 초과 시 추가 write 중단 | `maxQueueSize / 512`로 이벤트 capacity 환산, offer 실패 시 drop |
| 플러시 기준 | 큐 drain 후 전송, 비어 있으면 `sleepTime` 대기 | `queue.poll(sleepTime)` + 시간/건수 조건 |
| 배치 건수 제한 | `maxBatchSize`, `-1`이면 무제한 | `maxBatchSize`, `-1`이면 시간 기준 flush |
| 바이트 기준 배치 제한 | 없음 | 없음 |
| 종료 처리 | shutdown hook에서 `stop()` 및 publisher close | `stop()`에서 writer interrupt 후 남은 큐 flush |

주의: 두 구현 모두 장기간 OpenSearch 장애 시 로그 유실 가능성이 있다. `logback-elasticsearch`는 send buffer가 `maxQueueSize`를 넘으면 drop 모드가 되고, `simple-lib`는 bounded queue가 가득 차면 이벤트를 drop한다.

---

## 5. HTTP / Bulk 응답 처리

| 항목 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender |
|---|---|---|
| HTTP 성공 기준 | `200`만 성공 | `400` 미만이면 bulk body 분석 |
| HTTP 4xx | send buffer clear 후 실패 보고 | fatal failure 처리, 재시도하지 않음 |
| HTTP 429 | 4xx 범위라 drop 처리 | retryable 처리 |
| HTTP 5xx | 실패 보고 후 재시도 대상 | retryable 처리 |
| Bulk body `errors:false` | 성공 | 성공 |
| Bulk body `errors:true` | HTTP 200이면 성공처럼 처리될 수 있음 | `items`별 status 분석 |
| Partial failure 재시도 | 미지원 | `429` 또는 `5xx` item만 재시도 |
| fatal item 처리 | 별도 item 분리 없음 | `4xx` item은 fatal 메시지로 보고 |
| 재시도 delay | `sleepTime` 기반 루프 | `sleepTime` 고정 대기 |
| Dead letter 저장 | 없음 | 없음 |

`simple-lib 3.0.0`의 가장 큰 기능 차이는 OpenSearch `_bulk` 응답의 `items` 배열을 분석한다는 점이다. 이 덕분에 HTTP 200이지만 일부 문서만 실패한 경우도 감지할 수 있다.

---

## 6. 인증 / SSL / 헤더

| 항목 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender |
|---|---|---|
| 인증 인터페이스 | `Authentication` | `OpenSearchAuthentication` |
| Basic 인증 | `BasicAuthentication` | `OpenSearchBasicAuthentication` |
| Basic 설정 | `<username>`, `<password>` 또는 URL userinfo | `<username>`, `<password>` 또는 URL userinfo |
| AWS 인증 | `AWSAuthentication` 제공 | 미지원 |
| 커스텀 인증 확장 | `Authentication` 구현체 주입 | `OpenSearchAuthentication` 구현체 주입 |
| 커스텀 HTTP 헤더 | `<headers><header><name/><value/>` | `<headers><header><name/><value/>` |
| gzip 전송 | `Content-Encoding: gzip` 헤더가 있으면 실제 gzip 압축 | 커스텀 헤더는 가능하지만 gzip 압축 구현은 없음 |
| SSL trust-all | 미지원 | `trustAllSsl`, 기본 `true` |
| HostnameVerifier 해제 | 미지원 | `trustAllSsl=true`일 때 적용 |

---

## 7. 문서 필드 구성

| 필드 / 기능 | logback-elasticsearch-appender | simple-lib-spring-opensearch-appender |
|---|---|---|
| `@timestamp` | 기본 포함 | 기본 포함 |
| `message` | 기본 포함 | 기본 포함 |
| `level` | 기본 필드 아님. properties로 보통 추가 | 기본 포함 |
| `thread` | 기본 필드 아님. properties로 보통 추가 | 기본 포함 |
| `logger` | 기본 필드 아님. properties로 보통 추가 | 기본 포함 |
| 예외 클래스 / 메시지 / stack trace | 기본 필드 아님. properties로 `%ex` 등 추가 | `exception_class`, `exception_message`, `stack_trace` 기본 포함 |
| Caller data | `includeCallerData=true`로 event에 caller 준비, 필드는 properties로 구성 필요 | `includeCallerData=true`면 `caller_class`, `caller_method`, `caller_file`, `caller_line` 기본 포함 |
| MDC | `includeMdc=true`, 값은 문자열 | `includeMdc=true`, 일부 키 숫자/불리언 변환 |
| KVP | `includeKvp=true` | `includeKvp=true` |
| StructuredArgs | 전용 appender 사용 | 전용 appender 또는 `includeStructuredArgs=true` |
| custom properties | PatternLayout 기반 | PatternLayout 기반 |
| 고정 app/env/instance_id | 없음 | 없음 |

`simple-lib`의 MDC 타입 변환 대상:

- 숫자: `http_status`, `duration_ms`, `elapsed_ms`, `retry_count`, `status`
- 불리언: `success`, `retryable`

---

## 8. 설정 파라미터 전체 대조

### 8-1. 필수 / 연결

| 파라미터 | logback 기본값 | simple-lib 기본값 | 비고 |
|---|---:|---:|---|
| `url` | 필수 | 필수 | logback은 bulk endpoint를 직접 지정. simple-lib는 `/_bulk` 자동 보정 |
| `index` | 필수 | 필수 | 둘 다 Logback PatternLayout/날짜 패턴 용도 |
| `type` | `null` | `null` | 둘 다 bulk action meta의 `_type`에 반영 가능 |
| `connectTimeout` | `30000` | `30000` | ms |
| `readTimeout` | `30000` | `30000` | ms |
| `headers` | 빈 목록 | 빈 목록 | 커스텀 HTTP header |

### 8-2. 인증 / SSL

| 파라미터 | logback | simple-lib | 비고 |
|---|---|---|---|
| `authentication` | `Authentication` | `OpenSearchAuthentication` | XML class명은 다름 |
| `BasicAuthentication.username` | 지원 | 지원 | 명시 설정 우선 |
| `BasicAuthentication.password` | 지원 | 지원 | 명시 설정 우선 |
| URL userinfo 인증 | 지원 | 지원 | username/password 미지정 시 fallback |
| `AWSAuthentication` | 지원 | 미지원 | AWS SDK 필요 |
| `trustAllSsl` | 미지원 | `true` | simple-lib 전용 |

### 8-3. 큐 / 배치 / 재시도

| 파라미터 | logback 기본값 | simple-lib 기본값 | 비고 |
|---|---:|---:|---|
| `sleepTime` | `250` | `250` | ms, 둘 다 최소 100ms 보정 |
| `maxRetries` | `3` | `3` | simple-lib는 음수 입력 시 0으로 보정 |
| `maxQueueSize` | `104857600` | `104857600` | logback은 send buffer 문자 수, simple-lib는 이벤트 큐 capacity 계산 기준 |
| `maxBatchSize` | `-1` | `-1` | `-1`은 무제한 의미 |
| `maxMessageSize` | `-1` | `-1` | 0 이하이면 truncation 없음 |

### 8-4. 로그 내용 옵션

| 파라미터 | logback 기본값 | simple-lib 기본값 | 비고 |
|---|---:|---:|---|
| `includeMdc` | `false` | `true` | 기본값 차이 큼 |
| `includeKvp` | `false` | `false` | SLF4J fluent API key-value |
| `includeCallerData` | `false` | `false` | simple-lib는 caller 필드까지 직접 출력 |
| `rawJsonMessage` | `false` | `false` | logback은 raw write, simple-lib는 JSON 파싱 실패 시 문자열 fallback |
| `timestampFormat` | `null` | `null` | `long`이면 epoch ms |
| `autoStackTraceLevel` | `OFF` | `OFF` | 이 레벨 이상이면 예외 없이 stack trace 자동 생성 |

### 8-5. Bulk action

| 파라미터 | logback 기본값 | simple-lib 기본값 | 지원 값 |
|---|---:|---:|---|
| `operation` | `create` | `create` | `index`, `create`, `update`, `delete` |

주의: 두 구현 모두 `update` / `delete` action 이름은 만들 수 있지만, 일반 로그 document를 그대로 두 번째 줄에 쓰는 구조다. Elasticsearch/OpenSearch bulk API의 update/delete 세부 payload 요구사항과 운영 인덱스 정책은 별도 검증이 필요하다.

### 8-6. StructuredArgs / object serialization

| 파라미터 | logback 기본값 | simple-lib 기본값 | 비고 |
|---|---:|---:|---|
| `keyPrefix` | `null` | `""` | StructuredArgs field prefix |
| `objectSerialization` | `false` | `false` | true면 Jackson module 등록 |
| `includeStructuredArgs` | 없음 | `false` | simple-lib 전용. 기본 appender에서도 StructuredArgs 처리 가능 |

### 8-7. 미러링 / 에러 보고

| 파라미터 | logback 기본값 | simple-lib 기본값 | 비고 |
|---|---:|---:|---|
| `logsToStderr` | `false` | `false` | 전송 payload stderr 출력 |
| `errorsToStderr` | `false` | `false` | 내부 오류 stderr 출력 |
| `loggerName` | `null` | `null` | payload를 지정 logger로 mirror |
| `errorLoggerName` | `null` | `null` | 오류를 지정 logger로 mirror |

### 8-8. custom properties

| 하위 파라미터 | logback 기본값 | simple-lib 기본값 | 비고 |
|---|---:|---:|---|
| `properties.property.name` | 필수 | 필수 | logback은 `property`와 `esProperty` 모두 허용 |
| `properties.property.value` | 필수 | 필수 | PatternLayout 표현식 사용 |
| `properties.property.allowEmpty` | `false` | `true` | 기본값 차이 있음 |
| `properties.property.type` | `STRING` | `STRING` | `STRING`, `INT`, `FLOAT`, `BOOLEAN` |

---

## 9. 기능별 차이 상세

### 9-1. simple-lib 3.0.0이 추가로 유리한 지점

| 기능 | 설명 |
|---|---|
| `trustAllSsl` | 내부망, self-signed OpenSearch 인증서 환경에서 별도 truststore 없이 동작 |
| Bulk partial failure 분석 | HTTP 200 응답 안의 item별 `status`를 보고 429/5xx만 재시도 |
| 기본 문서 필드 풍부 | `level`, `thread`, `logger`, 예외, caller data 등을 기본 구조로 제공 |
| MDC 타입 보정 | 특정 MDC key를 숫자/불리언으로 저장해 Dashboards 집계 품질 개선 |
| `includeStructuredArgs` | appender class 교체 없이 StructuredArgs 처리 가능 |
| logstash encoder optional | StructuredArgs 미사용 앱에서는 런타임 의존 부담 감소 |

### 9-2. logback-elasticsearch-appender 3.0.19가 더 넓게 지원하는 지점

| 기능 | 설명 |
|---|---|
| AWSAuthentication | Amazon Elasticsearch/OpenSearch Service IAM 서명 인증 가능 |
| Logback Access | `IAccessEvent`용 `ElasticsearchAccessAppender` 제공 |
| gzip 전송 | `Content-Encoding: gzip` 헤더 설정 시 실제 gzip으로 body 전송 |
| Java 8 | 구형 런타임 호환성 |
| Maven Central 성격 | 외부 공개 라이브러리로 재사용/업데이트 경로가 명확 |
| 구 패키지 래퍼 | `com.internetitem.*` 호환 wrapper 제공 |

---

## 10. 설정 호환성 체크리스트

`logback-elasticsearch-appender` 설정에서 `simple-lib 3.0.0`으로 갈 때:

| 항목 | 조치 |
|---|---|
| appender class | `com.agido.logback.elasticsearch.ElasticsearchAppender` → `com.cube.opensearch.OpenSearchAppender` |
| StructuredArgs class | `StructuredArgsElasticsearchAppender` → `StructuredArgsOpenSearchAppender` 또는 `includeStructuredArgs=true` |
| Basic auth class | `com.agido.logback.elasticsearch.config.BasicAuthentication` → `com.cube.opensearch.OpenSearchBasicAuthentication` |
| AWS auth | 대체 구현 필요. 현재 simple-lib에는 없음 |
| Logback Access | 대체 구현 필요. 현재 simple-lib에는 없음 |
| `trustAllSsl` | self-signed OpenSearch면 simple-lib에서 추가 설정 가능 |
| `includeMdc` | 기본값이 다르므로 명시 권장 |
| `properties.property.allowEmpty` | 기본값이 다르므로 명시 권장 |
| `url` | simple-lib는 `/_bulk` 자동 추가. 기존 URL이 이미 `/_bulk`면 그대로 사용 |
| gzip header | simple-lib는 gzip 압축을 하지 않으므로 `Content-Encoding: gzip` 사용 금지 |

---

## 11. 운영 관점 권장

| 상황 | 권장 |
|---|---|
| 로컬 Docker / 내부망 OpenSearch / self-signed 인증서 | `simple-lib 3.0.0`이 편함 |
| AWS IAM 서명이 필요한 OpenSearch Service | `logback-elasticsearch-appender` 또는 simple-lib 인증 구현 추가 |
| HTTP access log를 Logback Access로 직접 수집 | `logback-elasticsearch-appender` |
| `_bulk` partial failure를 반드시 감지해야 함 | `simple-lib 3.0.0` |
| Java 8 애플리케이션 | `logback-elasticsearch-appender` |
| Java 21 + Logback 1.5 계열 앱 | `simple-lib 3.0.0` |

---

## 12. 보완 후보

`simple-lib 3.0.0`을 `logback-elasticsearch-appender` 대체재로 더 완성하려면 다음이 우선순위다.

1. `AWSAuthentication` 대응 구현 추가
2. gzip body 전송 지원
3. `ElasticsearchAccessAppender`에 해당하는 Access/Web appender 제공 여부 결정
4. `properties.property.allowEmpty` 기본값을 logback과 맞출지 검토
5. `includeMdc` 기본값을 logback과 맞출지, 현재처럼 OpenSearch 운영 편의성을 우선할지 명시
6. `update` / `delete` operation의 payload 정책 테스트 추가
7. partial failure, 429, 5xx, 4xx, malformed bulk response에 대한 단위 테스트 추가
