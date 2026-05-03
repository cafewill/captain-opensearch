# OpenSearch Appender 라이브러리 스펙 비교

이 문서는 Spring Boot용 Appender 3종의 제공 기능과 설정값을 비교한다.

- 원본: `lib/logback-elasticsearch-appender-3.0.19`
- Elasticsearch whole 1.0.0 OpenSearch 포팅: `lib/simple-lib-spring-opensearch-appender-whole-1.0.0`
- Elasticsearch bulk 1.0.0 OpenSearch 포팅: `lib/simple-lib-spring-opensearch-appender-bulk-1.0.0`
- 원본 동일 기능 커스터마이징: `lib/simple-lib-spring-opensearch-appender-3.0.0`
- 로그 모니터링 전용 bulk 구현: `lib/simple-lib-spring-opensearch-appender-bulk-3.0.0`

`simple-lib-spring-opensearch-appender-bulk-1.0.0`은 `simple-lib-spring-elasticsearch-appender-bulk-1.0.0`의 네이밍을 OpenSearch 기준으로 전환한 버전이다. 패키지는 `com.cube.simple.opensearch`, appender 클래스는 `com.cube.simple.opensearch.OpenSearchAppender`, 인증 클래스는 `com.cube.simple.opensearch.config.BasicAuthentication`을 사용한다. 이 버전은 bulk operation을 `index`/`create`로 제한하지만, `persistentWriterThread`/`requeueOnFailure` 같은 3.0.0 bulk 확장 설정은 포함하지 않는다.

---

## 0. 하위 호환 및 동일 운영을 위한 설정 (Compatibility Guide)

`bulk-3.0.0` 버전은 로그 모니터링 최적화를 위해 일부 기본값이 다르고 추가 기능(재큐, 스레드 유지 등)이 포함되어 있습니다.  
만약 **원본(`3.0.19`) 또는 원본 커스텀(`3.0.0`) 버전과 동일한 인프라 동작 방식**으로 운영하고 싶다면, `application.properties`에서 아래 설정을 지정하십시오.

### bulk 3.0.0 → 원본(3.0.19)과 동일한 인프라 동작으로 맞추는 설정

로그 적재 방식(`index`) 및 상세 모니터링(`MDC`) 기능은 그대로 활용하면서, **내부 스레드 모델과 실패 처리 방식만** 원본과 동일하게 맞추는 권장 설정입니다.

```properties
# 1. 전용 기능인 '실패 항목 재큐' 비활성화 (원본에는 없는 기능)
opensearch.requeue-on-failure=false

# 2. 백그라운드 스레드를 계속 유지하지 않고 필요할 때만 생성 (원본 방식)
opensearch.persistent-writer-thread=false

# 3. SSL 모든 인증서 허용 유지 (프라이빗 클라우드 운영 환경 필수)
opensearch.trust-all-ssl=true
```

### 주요 차이점 요약 (동일 운영 시 참고)
- **Operation & MDC**: 이 프로젝트는 로그 유실 방지 및 가시성을 위해 `index`와 `MDC` 사용을 기본으로 권장하며, 원본과 달리 이 설정들은 `true` 또는 `index`로 유지하는 것을 추천합니다.
- **Thread**: `bulk`는 성능을 위해 스레드를 상주시키지만(`true`), 원본은 로그가 없을 때 스레드를 종료하는 방식(`false`)을 사용합니다. 리소스가 극도로 제한된 환경에서는 `false`로 설정할 수 있습니다.
- **SSL**: 본 프로젝트의 모든 라이브러리는 프라이빗 클라우드 대응을 위해 `trust-all-ssl=true`를 기본 권장값으로 사용합니다.

---

## 1. 요약 비교

| 항목 | logback-elasticsearch-appender 3.0.19 | simple-lib-spring-opensearch-appender 3.0.0 | simple-lib-spring-opensearch-appender-bulk 3.0.0 |
|---|---|---|---|
| 목적 | Logback 이벤트를 Elasticsearch `_bulk` API로 전송하는 원본 | 원본 기능을 OpenSearch 명칭과 패키지로 커스터마이징 | 프로젝트 로그 모니터링 전용으로 bulk index/create 중심 동작 보강 |
| 대표 Appender | `ElasticsearchAppender`, `ElasticsearchAccessAppender`, `StructuredArgsElasticsearchAppender` | `OpenSearchAppender`, `StructuredArgsOpenSearchAppender` | `OpenSearchAppender`, `StructuredArgsOpenSearchAppender` |
| 전송 API | 설정한 `url`로 POST. 일반적으로 `/_bulk` URL 지정 필요 | `url` 끝에 `/_bulk`가 없으면 자동 추가 | `url` 끝에 `/_bulk`가 없으면 자동 추가 |
| 기본 operation | `create` | `create` | `index` |
| 허용 operation | `index`, `create`, `update`, `delete` | `index`, `create`, `update`, `delete` | `index`, `create` |
| bulk 응답 item별 분석 | 응답 코드 200이면 성공 처리, item별 partial failure 분석 없음 | `errors=true` 응답의 item별 상태 분석 | `errors=true` 응답의 item별 상태 분석 |
| 재시도 기준 | 전송 실패 또는 pending buffer가 남은 경우 반복 | HTTP 429/5xx, bulk item 429/5xx는 재시도 | HTTP 429/5xx, bulk item 429/5xx는 재시도 |
| 재큐 | 없음 | 없음 | 있음. `requeueOnFailure=true`일 때 재시도 초과/중단/예외 항목을 내부 큐에 재삽입 |
| 4xx 처리 | HTTP 4xx는 버퍼를 비우고 drop | HTTP 4xx 또는 item 4xx는 fatal 처리 후 drop | HTTP 4xx 또는 item 4xx는 fatal 처리 후 drop |
| 큐 구조 | 문자열 전송 버퍼. `maxQueueSize`는 문자 길이 기준 | `ArrayBlockingQueue<ILoggingEvent>`. capacity는 `maxQueueSize / 512`, 최소 100건 | `ArrayBlockingQueue<ILoggingEvent>`. capacity는 `maxQueueSize / 512`, 최소 100건 |
| writer thread | 이벤트 발생 시 writer thread 시작, idle 시 종료 | 기본 false: 원본 호환. true: daemon writer thread 유지 | 기본 true: daemon writer thread 유지 |
| SSL trust-all | 없음 | 있음. 기본 true | 있음. 기본 true |
| Basic 인증 | 있음 | 있음 | 있음 |
| 커스텀 헤더 | 있음 | 있음 | 있음 |
| MDC 포함 | 지원. 기본 false | 지원. 기본 true | 지원. 기본 true |
| SLF4J key-value 포함 | 지원. 기본 false | 지원. 기본 false | 지원. 기본 false |
| StructuredArguments | 지원 | 지원. 클래스패스에 `logstash-logback-encoder`가 없으면 structured args만 무시 | 지원. 클래스패스에 `logstash-logback-encoder`가 없으면 structured args만 무시 |
| caller data | 지원. 기본 false | 지원. 기본 false | 지원. 기본 false |
| raw JSON message | 지원. 기본 false | 지원. 기본 false | 지원. 기본 false |
| auto stack trace | 지원. 기본 OFF | 지원. 기본 OFF | 지원. 기본 OFF |
| OpenSearch 2.x/3.x 로그 모니터링 적합성 | 원본은 Elasticsearch 명칭과 create 기본값 중심 | OpenSearch 명칭으로 이식했지만 update/delete도 허용 | 이 프로젝트 권장. bulk index 기본, partial failure 재시도, 재큐 지원 |

## 2. 기능별 상세 비교

### 2-1. Bulk 전송

| 기능 | 원본 3.0.19 | simple 3.0.0 | bulk 3.0.0 |
|---|---|---|---|
| payload 형식 | NDJSON. action line + document line 반복 | NDJSON. action line + document line 반복 | NDJSON. action line + document line 반복 |
| endpoint | `url` 값을 그대로 사용 | `url`이 `/_bulk`로 끝나지 않으면 `/_bulk` 자동 추가 | `url`이 `/_bulk`로 끝나지 않으면 `/_bulk` 자동 추가 |
| 성공 판단 | HTTP 200이면 성공 | HTTP 2xx + bulk response `errors=false`이면 성공 | HTTP 2xx + bulk response `errors=false`이면 성공 |
| HTTP 429/5xx | 재시도 대상 | 재시도 대상 | 재시도 대상 |
| HTTP 4xx | 버퍼 삭제 후 실패 로그 | fatal 실패, 재시도 없음 | fatal 실패, 재시도 없음 |
| item 429/5xx | 별도 분석 없음 | 해당 item만 재시도 | 해당 item만 재시도 |
| item 4xx | 별도 분석 없음 | fatal 실패 메시지로 기록, 재시도 없음 | fatal 실패 메시지로 기록, 재시도 없음 |

### 2-2. Operation

| operation | 원본 3.0.19 | simple 3.0.0 | bulk 3.0.0 | 설명 |
|---|---|---|---|---|
| `index` | 지원 | 지원 | 지원 | 같은 `_id`가 있으면 덮어쓸 수 있는 bulk index 작업. 현재 프로젝트 로그 적재 기본 방식 |
| `create` | 지원, 기본값 | 지원, 기본값 | 지원 | 같은 `_id`가 있으면 version conflict가 날 수 있는 생성 전용 작업 |
| `update` | 지원 | 지원 | 미지원 | 로그 신규 적재용으로는 부적합. bulk에서는 설정 시 `index`로 대체 |
| `delete` | 지원 | 지원 | 미지원 | 로그 신규 적재용으로는 부적합. bulk에서는 설정 시 `index`로 대체 |
| 잘못된 값 | 경고 후 `create` 사용 | 경고 후 `create` 사용 | 경고 후 `index` 사용 | 빈 값도 같은 기본값으로 대체 |

## 3. 설정값 비교

단위 표기:

- `ms`: millisecond. 1000 ms = 1 sec
- `byte`: 바이트. 1024 byte = 1 KB, 1048576 byte = 1 MB
- `char`: Java `StringBuilder.length()` 기준 문자 수
- `event`: Logback `ILoggingEvent` 1건

### 3-1. 연결/인증 설정

| 설정값 | 단위/타입 | 원본 기본값 | simple 기본값 | bulk 기본값 | 기능 및 변경 시 동작 | 초과/오류 시 동작 |
|---|---:|---:|---:|---:|---|---|
| `url` | URL/String | 없음 | 없음 | 없음 | 전송 대상 URL. 원본은 값을 그대로 사용하고, simple/bulk는 `/_bulk`가 없으면 자동 추가 | 비어 있으면 simple/bulk는 appender 시작 실패. 원본은 출력 writer가 생성되지 않거나 URL 설정 오류 발생 |
| `index` | String | 없음 | 없음 | 없음 | bulk action의 `_index`. `%date{yyyy.MM.dd}` 또는 `{date}` 패턴으로 일자 치환 가능 | 비어 있으면 simple/bulk는 appender 시작 실패 |
| `type` | String | 없음 | 없음 | 없음 | bulk action metadata의 `_type`. OpenSearch 2.x/3.x에서는 일반적으로 사용하지 않음 | 값이 있으면 그대로 `_type`에 포함 |
| `authentication` | Object | 없음 | 없음 | 없음 | Basic/AWS 등 인증 헤더 추가. simple/bulk는 `OpenSearchBasicAuthentication` 제공 | 인증 실패는 보통 HTTP 401/403. 원본은 4xx에서 버퍼 drop, simple/bulk는 fatal 처리 |
| `headers` | Object list | 없음 | 없음 | 없음 | 커스텀 HTTP 헤더 추가. 원본은 `Content-Encoding: gzip` 지정 시 gzip 전송 | 잘못된 헤더명/빈 이름은 simple/bulk에서 무시 |
| `connectTimeout` | ms | 30000 ms = 30 sec | 30000 ms = 30 sec | 30000 ms = 30 sec | TCP 연결 대기 시간. 작게 하면 장애 감지가 빠르고, 크게 하면 느린 네트워크를 더 기다림 | 시간 초과 시 전송 예외. 재시도 대상 |
| `readTimeout` | ms | 30000 ms = 30 sec | 30000 ms = 30 sec | 30000 ms = 30 sec | 응답 읽기 대기 시간. 큰 bulk나 느린 OpenSearch에서는 늘릴 수 있음 | 시간 초과 시 전송 예외. 재시도 대상 |
| `trustAllSsl` | boolean | 미지원 | `true` | `true` | 자가 서명 인증서와 hostname 검증 우회. 프라이빗 클라우드 테스트용 | `false`에서 인증서 검증 실패 시 전송 예외. 재시도 대상 |

### 3-2. 큐/배치/전송 주기 설정

| 설정값 | 단위/타입 | 원본 기본값 | simple 기본값 | bulk 기본값 | 기능 및 변경 시 동작 | 초과/오류 시 동작 |
|---|---:|---:|---:|---:|---|---|
| `sleepTime` | ms | 250 ms | 250 ms | 250 ms | writer loop 대기/flush/retry 간격. 낮추면 지연은 줄고 CPU/전송 빈도는 증가 | 원본/simple/bulk 모두 100 ms 미만이면 100 ms로 보정 |
| `maxRetries` | count | 3 | 3 | 3 | 실패 전송 재시도 횟수. 실제 시도는 최초 1회 + 재시도 N회 | simple/bulk는 음수 입력 시 0으로 보정. 재시도 초과 시 simple은 drop, bulk는 설정에 따라 재큐 |
| `maxQueueSize` | 원본: char, simple/bulk: byte 환산값 | 104857600 = 100 MB 수준 | 104857600 = 100 MB, capacity 약 204800 event | 104857600 = 100 MB, capacity 약 204800 event | 원본은 전송 문자열 버퍼 최대 길이. simple/bulk는 `maxQueueSize / 512`로 event queue capacity 계산, 최소 100 event | 원본은 초과 후 버퍼가 비워질 때까지 신규 로그 유실. simple/bulk는 queue full이면 해당 event drop |
| `maxBatchSize` | event | -1 = 무제한 | -1 = 무제한 | -1 = 무제한 | 한 번의 bulk payload에 담을 최대 event 수. 양수면 해당 수 이상 모이면 flush | -1 또는 0 이하면 건수 제한 없음. 너무 크게 잡으면 payload와 메모리 사용 증가 |
| `persistentWriterThread` | boolean | 미지원 | `false` | `true` | true면 appender 생명주기 동안 daemon writer thread 유지. false면 이벤트 발생 시 thread 시작 후 idle 종료 | false에서 이벤트가 다시 들어오면 writer thread를 재기동 |
| `requeueOnFailure` | boolean | 미지원 | 미지원 | `true` | 재시도 초과/중단/예외 발생 시 실패 item을 내부 큐에 다시 넣음 | 재큐 시 큐가 가득 차면 재삽입 실패 event는 drop |

### 3-3. 메시지/필드 구성 설정

| 설정값 | 단위/타입 | 원본 기본값 | simple 기본값 | bulk 기본값 | 기능 및 변경 시 동작 | 초과/오류 시 동작 |
|---|---:|---:|---:|---:|---|---|
| `includeMdc` | boolean | `false` | `true` | `true` | MDC map을 OpenSearch 문서 필드로 추가 | simple/bulk는 `@timestamp`, `level`, `thread`, `logger`, `message` 같은 고정 필드 충돌 키를 무시 |
| `includeKvp` | boolean | `false` | `false` | `false` | SLF4J 2 key-value pair를 문서 필드로 추가 | null pair, 빈 key, 고정 필드 충돌 key는 simple/bulk에서 무시 |
| `includeCallerData` | boolean | `false` | `false` | `false` | caller class/method/file/line 추가. 호출 위치 계산 비용 증가 | caller data가 없으면 필드 미추가 |
| `rawJsonMessage` | boolean | `false` | `false` | `false` | true면 message를 JSON으로 파싱해 object/array로 저장 시도 | 파싱 실패 시 simple/bulk는 문자열 message로 저장 |
| `maxMessageSize` | char | -1 = 무제한 | -1 = 무제한 | -1 = 무제한 | message 최대 길이. 양수면 해당 문자 수까지만 보존 | 초과 시 앞부분 `N` char + `..`로 truncate |
| `timestampFormat` | String | 기본 `yyyy-MM-dd'T'HH:mm:ss.SSSZ`; `long` 가능 | 기본 ISO offset date-time, JVM 기본 timezone; `long` 가능 | 기본 ISO offset date-time, JVM 기본 timezone; `long` 가능 | 날짜 포맷 문자열 지정. `long`이면 epoch millis 숫자로 저장 | 잘못된 패턴은 이벤트 직렬화 중 예외 가능 |
| `keyPrefix` | String | 없음 | `""` | `""` | StructuredArguments 필드명 앞에 prefix 추가 | null이면 빈 문자열 처리 |
| `objectSerialization` | boolean | `false` | `false` | `false` | 객체 값을 Jackson tree로 직렬화. true면 Java time 등 모듈 등록 | 직렬화 불가 객체는 Jackson 예외 가능 |
| `includeStructuredArgs` | boolean | Appender 유형으로 제공 | `false` | `false` | true면 logstash `ObjectAppendingMarker` 인자를 필드화 | 관련 클래스가 classpath에 없으면 structured args만 무시 |
| `autoStackTraceLevel` | Logback level | `OFF` | `OFF` | `OFF` | 지정 레벨 이상 로그에 예외가 없어도 stack trace 자동 생성 | 잘못된 level은 Logback `Level.toLevel` 기준으로 `OFF` 처리 |
| `properties` | Object list | 없음 | 없음 | 없음 | PatternLayout 표현식 기반 고정/파생 필드 추가 | `allowEmpty=false`인데 결과가 blank면 필드 미추가. 숫자 변환 실패 시 해당 숫자 필드 미추가 |
| `loggerName` | String | 없음 | 없음 | 없음 | bulk payload를 지정 logger로 mirror 출력 | logger 미설정 시 출력 없음 |
| `errorLoggerName` | String | 없음 | 없음 | 없음 | 전송 실패/예외를 지정 logger로 출력 | logger 미설정 시 출력 없음 |
| `logsToStderr` | boolean | `false` | `false` | `false` | bulk payload를 stderr로 출력 | 운영에서는 payload 노출 위험 |
| `errorsToStderr` | boolean | `false` | `false` | `false` | 전송 오류를 stderr로 출력 | 운영 stderr 로그 증가 |

## 4. 장애/한도 초과 시 동작

| 상황 | 원본 3.0.19 | simple 3.0.0 | bulk 3.0.0 |
|---|---|---|---|
| OpenSearch 연결 실패 | send buffer 유지 후 `maxRetries`까지 재시도. 초과 시 writer 종료 가능 | 현재 batch를 `maxRetries`만큼 재시도 후 drop | 현재 batch를 `maxRetries`만큼 재시도 후 `requeueOnFailure=true`면 큐에 재삽입 |
| HTTP 429 또는 5xx | 재시도 | 재시도 | 재시도 |
| HTTP 400~499 | send buffer 삭제 후 drop | fatal 처리 후 drop | fatal 처리 후 drop |
| bulk response item 일부 429/5xx | HTTP 200이면 성공으로 간주할 수 있음 | 실패 item만 재시도 | 실패 item만 재시도 |
| bulk response item 일부 400~499 | HTTP 200이면 성공으로 간주할 수 있음 | fatal 메시지 기록 후 해당 item drop | fatal 메시지 기록 후 해당 item drop |
| 큐/버퍼 초과 | `maxQueueSize` 이상이면 버퍼가 비워질 때까지 신규 로그 유실 | 내부 event queue full이면 신규 event drop | 내부 event queue full이면 신규 event drop. 재큐도 queue full이면 drop |
| message 길이 초과 | `maxMessageSize > 0`이면 truncate | `maxMessageSize > 0`이면 truncate | `maxMessageSize > 0`이면 truncate |
| `sleepTime < 100` | 100 ms로 보정 | 100 ms로 보정 | 100 ms로 보정 |
| `maxQueueSize <= 0` | 코드상 그대로 설정될 수 있어 비권장 | 104857600 byte 기본값으로 보정 | 104857600 byte 기본값으로 보정 |
| 잘못된 operation | `create`로 대체 | `create`로 대체 | `index`로 대체 |

## 5. 권장 선택 기준

| 사용 목적 | 권장 라이브러리 | 이유 |
|---|---|---|
| 원본 동작 검증 또는 레퍼런스 확인 | `lib/logback-elasticsearch-appender-3.0.19` | upstream 기능 기준점 |
| Elasticsearch 1.0.0 whole 포팅 검증 | `lib/simple-lib-spring-opensearch-appender-whole-1.0.0` | 기존 `simple-lib-spring-elasticsearch-appender-whole-1.0.0`의 OpenSearch 네이밍 전환본 |
| Elasticsearch 1.0.0 bulk 포팅 검증 | `lib/simple-lib-spring-opensearch-appender-bulk-1.0.0` | 기존 `simple-lib-spring-elasticsearch-appender-bulk-1.0.0`의 OpenSearch 네이밍 전환본, `index`/`create`만 허용 |
| 원본과 최대한 동일한 OpenSearch 명칭 커스터마이징 검증 | `lib/simple-lib-spring-opensearch-appender-3.0.0` | create/update/delete 포함 원본 operation 범위 유지 |
| 프로젝트 로그 모니터링 운영 예제 | `lib/simple-lib-spring-opensearch-appender-bulk-3.0.0` | bulk `index` 기본, partial failure 분석, retry, 재큐, persistent writer 기본값 제공 |
| 로컬 `.m2` 설치 없이 바로 빌드·실행 | `simple-jobs-spring-maven-full` / `simple-jobs-spring-maven-bulk` | 라이브러리 소스를 프로젝트에 직접 내장 |

---

## 6. 소스 내장 변형 (simple-jobs-spring-maven-full / bulk)

`lib/` 공용 라이브러리를 로컬 `.m2`에 설치하지 않고도 즉시 빌드·실행할 수 있도록,  
라이브러리 소스를 `com.cube.simple.opensearch` 패키지로 Spring Boot 프로젝트에 직접 내장한 변형이다.

### 6-1. 내장 구성 비교

| 항목 | `simple-jobs-spring-maven-full` | `simple-jobs-spring-maven-bulk` |
|---|---|---|
| 기반 라이브러리 소스 | `lib/simple-lib-spring-opensearch-appender-3.0.0` | `lib/simple-lib-spring-opensearch-appender-bulk-3.0.0` |
| 내장 패키지 | `com.cube.simple.opensearch` | `com.cube.simple.opensearch` |
| 내장 파일 수 | 12개 Java 소스 | 12개 Java 소스 (3개 파일 내용 다름) |
| pom.xml 외부 lib 의존성 | 없음 (제거됨) | 없음 (제거됨) |
| pom.xml 추가 의존성 | `jackson-databind` (Spring Boot BOM 관리) | `jackson-databind` (Spring Boot BOM 관리) |
| logback-spring.xml appender class | `com.cube.simple.opensearch.OpenSearchAppender` | `com.cube.simple.opensearch.OpenSearchAppender` |

### 6-2. 원본 라이브러리와의 소스 차이 (bulk 기준)

`simple-jobs-spring-maven-bulk` 에 내장된 소스는 `bulk-3.0.0` 기준이므로 full 변형과 다음 3개 파일이 다르다.

| 파일 | full 대비 bulk 차이 |
|---|---|
| `AbstractOpenSearchAppender.java` | `operation` 기본값 `index`, `persistentWriterThread` 기본값 `true`, `requeueOnFailure` 필드·setter 추가, `requeueOrDrop()` 메서드 추가, `setOperation()` 검증이 `index`/`create`만 허용 |
| `BulkPayloadBuilder.java` | `normalizeOperation()` 헬퍼 추가, operation 기본값 `index` |
| `OpenSearchSender.java` | `analyzeBulkResponse()` 에서 `index`/`create` node만 처리 (update/delete 제외) |

### 6-3. 실행 방법

```bash
# 소스 내장 full — 로컬 .m2 설치 없이 바로 실행
cd simple-jobs-spring-maven-full
cp src/main/resources/application-example.properties src/main/resources/application.properties
./mvnw spring-boot:run

# 소스 내장 bulk — 로컬 .m2 설치 없이 바로 실행
cd simple-jobs-spring-maven-bulk
cp src/main/resources/application-example.properties src/main/resources/application.properties
./mvnw spring-boot:run
```

### 6-4. 라이브러리 소스 수정 후 반영 방법

소스 내장 방식은 `lib/` 수정 후 별도 설치 과정이 없다.  
`src/main/java/com/cube/simple/opensearch/` 아래 해당 파일을 직접 수정한 뒤 재빌드하면 된다.

```bash
# 재빌드 (full 또는 bulk)
./mvnw package -DskipTests -q
```
