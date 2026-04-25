# lib/simple-lib-spring-opensearch-appender-3.0.0 vs lib/simple-lib-spring-opensearch-appender-bulk-only-3.0.0 리뷰

## 1. 결론

`lib/simple-lib-spring-opensearch-appender-bulk-only-3.0.0` 는 `lib/simple-lib-spring-opensearch-appender-3.0.0` 를 기반으로 하되, OpenSearch `_bulk` 액션을 `index` / `create` 로만 제한한 변형입니다.

패키지명, 클래스명, Logback 설정 파라미터명은 기존 `3.0.0` 과 동일하게 유지되어 있어 소비 앱의 `logback-spring.xml` 구조는 그대로 사용할 수 있습니다. 차이는 Maven artifactId 와 `<operation>` 허용 범위입니다.

## 2. Maven 좌표 비교

| 항목 | 3.0.0 | bulk-only 3.0.0 |
|---|---|---|
| groupId | `com.cube` | `com.cube` |
| artifactId | `simple-lib-spring-opensearch-appender` | `simple-lib-spring-opensearch-appender-bulk-only` |
| version | `3.0.0` | `3.0.0` |
| packaging | `jar` | `jar` |

## 3. 패키지 및 클래스 네이밍

두 라이브러리 모두 동일합니다.

| 유형 | 이름 |
|---|---|
| package | `com.cube.opensearch` |
| 기본 appender | `com.cube.opensearch.OpenSearchAppender` |
| structured args appender | `com.cube.opensearch.StructuredArgsOpenSearchAppender` |
| basic auth | `com.cube.opensearch.OpenSearchBasicAuthentication` |
| headers/properties | `OpenSearchHeaders`, `OpenSearchHeader`, `OpenSearchProperties`, `OpenSearchProperty` |

따라서 bulk-only 라이브러리를 사용해도 Logback appender class 명은 바뀌지 않습니다.

## 4. 설정 파라미터 비교

설정 파라미터명은 동일합니다.

| 설정 | 3.0.0 | bulk-only 3.0.0 |
|---|---:|---:|
| `url` | 지원 | 지원 |
| `index` | 지원 | 지원 |
| `authentication` | 지원 | 지원 |
| `trustAllSsl` | 지원 | 지원 |
| `persistentWriterThread` | 지원 | 지원 |
| `includeMdc` | 지원 | 지원 |
| `includeKvp` | 지원 | 지원 |
| `includeCallerData` | 지원 | 지원 |
| `rawJsonMessage` | 지원 | 지원 |
| `autoStackTraceLevel` | 지원 | 지원 |
| `sleepTime` | 지원 | 지원 |
| `maxBatchSize` | 지원 | 지원 |
| `maxMessageSize` | 지원 | 지원 |
| `maxQueueSize` | 지원 | 지원 |
| `connectTimeout` | 지원 | 지원 |
| `readTimeout` | 지원 | 지원 |
| `maxRetries` | 지원 | 지원 |
| `operation` | 지원 | 지원, 단 `index` / `create` 만 허용 |
| `timestampFormat` | 지원 | 지원 |
| `properties` | 지원 | 지원 |
| `headers` | 지원 | 지원 |
| `keyPrefix` | 지원 | 지원 |
| `objectSerialization` | 지원 | 지원 |

## 5. operation 동작 비교

| operation 값 | 3.0.0 | bulk-only 3.0.0 |
|---|---|---|
| `index` | 허용 | 허용 |
| `create` | 허용 | 허용 |
| `update` | 허용 | 미허용, warning 후 `create` 로 fallback |
| `delete` | 허용 | 미허용, warning 후 `create` 로 fallback |
| blank/null | `create` 로 fallback | `create` 로 fallback |
| 기타 값 | warning 후 `create` 로 fallback | warning 후 `create` 로 fallback |

bulk-only 변형은 이름 그대로 문서 적재용 bulk 액션인 `index` / `create` 만 남겼습니다. 로그 appender 관점에서는 `update` / `delete` 보다 안전하고 예측 가능한 범위입니다.

## 6. 주요 소스 차이

### 6-1. `pom.xml`

bulk-only는 artifactId만 분리했습니다.

```xml
<artifactId>simple-lib-spring-opensearch-appender-bulk-only</artifactId>
```

### 6-2. `AbstractOpenSearchAppender`

`setOperation` 검증 범위가 변경되었습니다.

| 항목 | 3.0.0 | bulk-only 3.0.0 |
|---|---|---|
| 허용 operation | `index`, `create`, `update`, `delete` | `index`, `create` |
| invalid fallback | `create` | `create` |
| warning 메시지 | 일반 invalid 메시지 | bulk-only 제약을 명시 |

### 6-3. `BulkPayloadBuilder`

bulk-only는 내부적으로도 `operation` 을 한 번 더 정규화합니다.

```java
return lower.equals("index") || lower.equals("create") ? lower : "create";
```

Logback setter 우회나 내부 생성 경로에서 잘못된 값이 들어와도 최종 bulk action line은 `index` 또는 `create` 로 제한됩니다.

### 6-4. `OpenSearchSender`

bulk response 분석 대상도 `index` / `create` 만 남겼습니다.

| 항목 | 3.0.0 | bulk-only 3.0.0 |
|---|---|---|
| response item 검사 | `index`, `create`, `update`, `delete` | `index`, `create` |
| retry 판단 | 429 또는 5xx | 동일 |
| fatal 판단 | 4xx | 동일 |

## 7. 예시 설정 차이

`examples/logback-spring.xml` 의 설정 태그는 동일합니다. 주석만 bulk-only 목적에 맞게 바뀌었습니다.

```xml
<!-- 벌크 작업 방식 (bulk-only: index / create) -->
<operation>create</operation>
```

## 8. 운영 관점 평가

bulk-only 변형은 로그 적재 전용 appender로 쓰기에 더 보수적인 선택입니다.

- `create`: 중복 `_id` 가 없다는 전제에서 append-only 로그 적재에 적합합니다.
- `index`: 같은 `_id` 가 들어올 경우 덮어쓰기 가능성이 있으므로 재처리/동일 키 정책이 필요한 경우에만 선택하는 편이 낫습니다.
- `update` / `delete`: 로그 appender 목적과 맞지 않고 장애 시 데이터 보존 관점에서 위험할 수 있어 bulk-only에서 제외한 것이 합리적입니다.

## 9. 검증 결과

main 반영 후 아래 빌드를 통과했습니다.

```bash
cd lib/simple-lib-spring-opensearch-appender-bulk-only-3.0.0
../../simple-jobs-spring-maven/mvnw -q -DskipTests package
```

빌드 산출물 `target` 은 검증 후 정리했습니다.

## 10. 요약

| 비교 항목 | 결과 |
|---|---|
| 패키지/클래스명 동일성 | 동일 |
| 설정 파라미터 동일성 | 동일 |
| Maven artifactId | 분리됨 |
| bulk action 범위 | bulk-only는 `index` / `create` 전용 |
| 기존 소비 앱 설정 호환성 | class/parameter 기준 호환 |
| 기능 리스크 | `update` / `delete` 사용 앱은 bulk-only 전환 시 `create` 로 fallback 됨 |

