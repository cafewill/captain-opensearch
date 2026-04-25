# lib/simple-lib-python-opensearch-appender-1.0.0 vs lib/simple-lib-spring-opensearch-appender-bulk-only-3.0.0 리뷰

## 1. 결론

`lib/simple-lib-python-opensearch-appender-1.0.0` 과 `lib/simple-lib-spring-opensearch-appender-bulk-only-3.0.0` 은 둘 다 OpenSearch `_bulk` API로 로그를 적재하지만 목적과 통합 방식이 다릅니다.

Python 라이브러리는 Flask/FastAPI/job 앱에서 직접 인스턴스를 만들고 `log(...)` 를 호출하는 단순 appender입니다. bulk action은 코드상 `index` 로 고정되어 있습니다.

Spring bulk-only 라이브러리는 Logback appender로 동작하며, 기존 Spring `3.0.0` 설정 파라미터와 클래스명을 유지하면서 `_bulk` action을 `index` / `create` 로 제한한 변형입니다.

## 2. 패키징 비교

| 항목 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| 위치 | `lib/simple-lib-python-opensearch-appender-1.0.0` | `lib/simple-lib-spring-opensearch-appender-bulk-only-3.0.0` |
| 패키징 | Python package | Maven JAR |
| 식별자 | `opensearch-appender` | `com.cube:simple-lib-spring-opensearch-appender-bulk-only:3.0.0` |
| 런타임 | Python 3.8+ | Java 21 / Spring Boot / Logback |
| 외부 의존성 | 없음 | Logback, SLF4J provided, Jackson |
| 설정 방식 | 생성자 인자 / env 사용 앱에서 주입 | `logback-spring.xml` appender 설정 |

## 3. 제공 클래스/모듈 비교

| 영역 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| Job 로그 | `OpenSearchJobAppender` | `OpenSearchAppender` |
| Web 로그 | `OpenSearchFlaskWebAppender`, `OpenSearchFastapiWebAppender` | 별도 web 클래스 없음, Logback 로거 기반 |
| Structured args | 없음 | `StructuredArgsOpenSearchAppender` |
| 인증 | 생성자 `username/password` | `OpenSearchBasicAuthentication` |
| Headers | 없음 | `OpenSearchHeaders`, `OpenSearchHeader` |
| 고정 properties | 없음, `extra` 직접 전달 | `OpenSearchProperties`, `OpenSearchProperty` |

## 4. Bulk operation 비교

| 항목 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| bulk endpoint | `{url}/_bulk` | `{url}/_bulk` 자동 보정 |
| action line | `{"index": {"_index": ...}}` 고정 | `index` 또는 `create` |
| operation 설정 | 없음 | `<operation>index</operation>` 또는 `<operation>create</operation>` |
| invalid operation 처리 | 불필요 | warning 후 `create` fallback |
| `update/delete` | 미지원 | 미지원, 입력 시 `create` fallback |

Python 라이브러리는 사실상 bulk-only 중에서도 `index-only`에 가깝습니다. Spring bulk-only는 운영자가 `index`와 `create` 중 하나를 선택할 수 있습니다.

## 5. 설정 파라미터 비교

| 설정/기능 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---:|---:|
| `url` | 지원 | 지원 |
| `username/password` | 지원 | `authentication`으로 지원 |
| `app` | 지원 | `index` 패턴/고정 property로 구성 |
| `env` | 지원 | 고정 property로 구성 |
| `max_batch_bytes` / `maxBatchSize` | bytes 기준 지원 | event count 기준 `maxBatchSize`, message size 기준 `maxMessageSize` |
| `flush_interval_seconds` | 지원 | `sleepTime` 기반 polling |
| `queue_size` / `maxQueueSize` | event count 기준 지원 | byte-like 설정값을 내부 queue capacity로 환산 |
| `connectTimeout` | 고정 `urlopen(..., timeout=10)` | 설정 가능 |
| `readTimeout` | 고정 `urlopen(..., timeout=10)` | 설정 가능 |
| `maxRetries` | 실패 항목 다음 flush 재시도 | 즉시 retry loop 지원 |
| `trustAllSsl` | 항상 trust-all | 설정 가능, 기본 true |
| `headers` | 미지원 | 지원 |
| `properties` | 미지원 | 지원 |
| `includeMdc` | 미지원, 호출자가 extra 전달 | 지원 |
| `includeKvp` | 미지원 | 지원 |
| `includeCallerData` | 미지원 | 지원 |
| `rawJsonMessage` | 미지원 | 지원 |
| `timestampFormat` | 미지원 | 지원 |
| `persistentWriterThread` | daemon thread 항상 유지 | 설정 가능 |

## 6. 로그 필드 비교

### Python 1.0.0 기본 필드

Python appender는 공통 필드와 호출자가 넘기는 `extra`를 그대로 문서에 합칩니다.

| 필드 | 설명 |
|---|---|
| `@timestamp` | UTC ISO timestamp |
| `@timestamp_local` | 로컬 타임존 timestamp |
| `@timestamp_kst` | KST timestamp |
| `level` | 호출자가 넘긴 레벨 |
| `message` | 호출자가 넘긴 메시지 |
| `app` | 생성자 `app` |
| `env` | 생성자 `env` |
| `instance_id` | hostname |
| `**extra` | 호출자가 넘긴 추가 필드 |

Web appender는 `trace_id`, `http_method`, `http_path`, `client_ip`, `http_status`, `duration_ms`를 추가합니다.

### Spring bulk-only 3.0.0 기본 필드

Spring bulk-only는 Logback event를 기반으로 문서를 구성합니다.

| 필드/기능 | 설명 |
|---|---|
| `@timestamp` | event timestamp 기반 |
| `level`, `thread`, `logger`, `message` | Logback event 기본값 |
| exception fields | `exception_class`, `exception_message`, `stack_trace` |
| MDC fields | `includeMdc=true`일 때 MDC 주입 |
| KVP fields | `includeKvp=true`일 때 SLF4J key-value pair 주입 |
| structured args | `StructuredArgsOpenSearchAppender` 사용 시 structured arguments 처리 |
| properties | Logback pattern 기반 고정 필드 추가 |

## 7. Queue / writer thread 비교

| 항목 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| queue 구현 | `queue.Queue(maxsize=queue_size)` | `ArrayBlockingQueue` |
| writer thread | daemon thread 1개 항상 유지 | 기본은 필요 시 writer thread 기동, `persistentWriterThread=true`면 daemon thread 유지 |
| flush 기준 | `flush_interval_seconds`마다 전체 queue drain | `sleepTime`, `maxBatchSize`, queue 상태 기준 |
| 종료 처리 | `atexit.register(self.stop)` | Logback appender `stop()`에서 남은 queue flush |
| queue full 처리 | 조용히 drop | `addWarn`으로 drop 경고 |

Python 쪽은 단순하고 예측하기 쉽지만, queue full이나 부분 실패에 대한 관측성이 약합니다. Spring 쪽은 설정이 많고 동작이 복잡하지만 운영 중 문제를 더 잘 드러냅니다.

## 8. Retry / 실패 처리 비교

| 항목 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| HTTP 실패 | 예외 출력 후 retry list에 저장 | `SendResult.retryable`로 retry loop 수행 |
| bulk partial failure 분석 | 없음 | 응답 body의 `errors/items` 분석 |
| retry 대상 | 전송 실패 batch 전체 | 429 또는 5xx item |
| fatal 4xx 처리 | 별도 분석 없음 | fatal 메시지 구성 후 실패 처리 |
| retry 한도 | retry list 용량 기준 | `maxRetries` 설정 |

Spring bulk-only는 `_bulk` 응답을 읽고 item 단위로 429/5xx 재시도 여부를 판단합니다. Python 1.0.0은 HTTP 요청 자체가 실패한 경우만 재시도 목록에 넣고, OpenSearch가 200 응답 안에서 일부 item 실패를 돌려주는 경우는 분석하지 않습니다.

## 9. SSL / 인증 비교

| 항목 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| Basic auth | 지원 | 지원 |
| SSL 검증 | 항상 비활성화 | `trustAllSsl`로 설정 가능, 기본 true |
| custom headers | 미지원 | 지원 |
| timeout | `urlopen` timeout 10초 고정 | `connectTimeout`, `readTimeout` 설정 가능 |

내부망/자가서명 인증서 환경에서는 둘 다 바로 쓰기 쉽습니다. 외부망 또는 공인 인증서 검증이 필요한 환경에서는 Spring bulk-only가 `trustAllSsl=false`로 전환할 수 있어 더 유연합니다.

## 10. Web/API 관점 비교

Python 라이브러리는 Flask/FastAPI용 Web appender를 별도로 제공합니다.

| 기능 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---:|---:|
| request trace id 자동 생성 | 지원 |
| response header `X-Request-ID` | 지원 |
| request duration 측정 | 지원 |
| HTTP route/status 필드 | 지원 |
| Spring MVC filter 제공 | 없음 |

Spring bulk-only는 라이브러리 자체가 Logback appender입니다. API trace나 latency 필드는 소비 앱에서 Filter/Interceptor/MDC로 넣어야 합니다.

## 11. 운영 선택 기준

| 상황 | 더 적합한 선택 |
|---|---|
| Python Flask/FastAPI/Job 앱에서 최소 구현으로 로그 적재 | Python 1.0.0 |
| Spring Boot Logback 로그 전체를 OpenSearch로 적재 | Spring bulk-only 3.0.0 |
| `create` 기반 append-only 정책이 필요 | Spring bulk-only 3.0.0 |
| `index` 고정이어도 충분한 단순 Python 앱 | Python 1.0.0 |
| bulk partial failure, max retry, custom headers가 중요 | Spring bulk-only 3.0.0 |
| request trace/duration이 Python web 앱에 바로 필요 | Python 1.0.0 |
| MDC/KVP/structured args를 적극 활용 | Spring bulk-only 3.0.0 |

## 12. 주요 리스크

### Python 1.0.0

- bulk action이 `index` 고정이라 `create` 기반 중복 방지 정책을 설정할 수 없습니다.
- `_bulk` 200 응답 내부의 item별 partial failure를 분석하지 않습니다.
- SSL 검증이 항상 비활성화되어 있어 공인 인증서 환경에서는 보안 정책상 조정이 필요합니다.
- queue full 시 조용히 drop되므로 운영 관측성이 낮습니다.
- headers, MDC, KVP, structured args 같은 확장 설정은 없습니다.

### Spring bulk-only 3.0.0

- Python web appender처럼 request trace/duration을 자동 주입하지 않습니다.
- Logback/Spring 설정 의존성이 있어 단독 스크립트나 경량 Python 작업보다 무겁습니다.
- `update/delete` operation을 쓰던 설정은 bulk-only 전환 시 `create`로 fallback됩니다.
- 클래스명이 기존 Spring appender와 같으므로, 같은 앱에 두 artifact를 동시에 올리면 클래스 충돌 가능성이 큽니다.

## 13. 요약

| 비교 항목 | Python 1.0.0 | Spring bulk-only 3.0.0 |
|---|---|---|
| 핵심 성격 | 표준 라이브러리 기반 경량 appender | Logback 통합형 bulk-only appender |
| bulk action | `index` 고정 | `index` / `create` 선택 |
| 설정 확장성 | 낮음 | 높음 |
| 실패 분석 | HTTP 요청 실패 중심 | bulk item partial failure 분석 |
| Web 요청 추적 | Flask/FastAPI 전용 지원 | 소비 앱 MDC 구현 필요 |
| 보안 설정 | trust-all 고정 | trust-all 설정 가능 |
| 운영 관측성 | 단순 | 풍부 |

결론적으로 Python 1.0.0은 “가볍게 붙이는 표준 라이브러리 appender”이고, Spring bulk-only 3.0.0은 “Logback 기반 운영 설정과 bulk 실패 제어를 갖춘 appender”입니다. 두 라이브러리는 사용 언어와 통합 지점이 달라 직접 대체 관계라기보다, 각 런타임에 맞춘 구현 수준 차이로 보는 것이 맞습니다.

