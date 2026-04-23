# simple-lib-spring-opensearch-appender 2.0.0

이 샘플은 1.1.0의 패키지 구조와 주요 클래스명을 유지하면서 2.0.0 릴리스용으로 정리한 포팅 결과물입니다.

이번 수정본에서는 통합 본체인 `OpenSearchAppender`를 추가했습니다.

## 유지한 것
- 패키지: `com.cube.opensearch`
- 클래스명: `AbstractOpenSearchAppender`, `OpenSearchAppender`, `OpenSearchWebAppender`, `OpenSearchJobAppender`
- 오토컨피그 진입점: `OpenSearchWebAutoConfiguration`, `OpenSearchJobAutoConfiguration`
- 기존 setter 기반 Logback 설정 방식 유지
- `cube.opensearch.web.enabled`, `cube.opensearch.job.enabled` 하위호환 유지

## 2.0.0 추가점
- `timestampZone` setter 추가
- `includeMdc` setter 추가
- `maxBatchSize`, `maxMessageSize`, `operation`, `includeKvp`, `errorsToStderr` setter 추가
- `<headers>` / `<properties>` 중첩 설정 지원 추가
- `@timestamp`를 `UTC`, `Asia/Seoul` 등 `ZoneId`로 제어 가능
- 기존 설정이 없으면 기본값으로 동작하도록 보강

## SSL 정책
- `1.1.0` 철학을 유지하여 **기업 내부망 자가 서명 인증서 환경에서는 `trustAllSsl=true`를 기본값**으로 둡니다.
- 이 기본값은 루트 [README.md](/Users/nano/Desktop/practice/captain-opensearch-devel/README.md:6), [README.md](/Users/nano/Desktop/practice/captain-opensearch-devel/README.md:420) 에 적힌 내부망 VM / 추가 패키지 설치 불가 제약을 따른 선택입니다.
- 외부망, 공인 인증서, 엄격한 TLS 검증이 필요한 환경에서는 `setTrustAllSsl(false)` 또는 `opensearch.trust-all-ssl=false`로 명시적으로 끄세요.

## 신규 optional 설정
- `opensearch.timestamp-zone=UTC`
- `opensearch.include-mdc=true`
- `opensearch.trust-all-ssl=true`
- `opensearch.max-batch-size=200`
- `opensearch.max-message-size=32768`
- `opensearch.include-kvp=true`
- `opensearch.operation=create`
- `opensearch.errors-to-stderr=true`

셋 다 생략해도 동작합니다.

## 3.0.19 대비 반영한 핵심 축
- `_bulk` 응답의 partial failure 분석 및 retry/fatal 분기
- 배치 바이트 제한과 배치 건수 제한 동시 지원
- 메시지 길이 제한(`maxMessageSize`) 지원
- `create` / `index` operation 선택 지원
- MDC 외에 SLF4J KeyValuePair(`includeKvp`) 적재 지원
- Logback pattern 기반 custom property 적재 지원
- HTTP request header 추가 지원

## 하위호환성 정책
기존 `application.properties` / `logback-spring.xml`에서 쓰던 값만 있어도 실행에 문제 없도록 기본값을 유지했습니다.
추가된 2.0.0 옵션은 선택사항입니다.

## 주의
이 프로젝트는 실제 운영 반영 전 검토용 초안입니다. 인증, TLS, 대량 전송, index lifecycle, 비동기 종료 처리 등은 운영 요구사항에 맞게 보강하세요.


## 2.0.0 통합 방향
- 신규 본체: `OpenSearchAppender`
- 기존 `OpenSearchWebAppender`, `OpenSearchJobAppender`는 하위호환용 래퍼로 유지
- 공통 전송 로직은 `OpenSearchAppender`에 통합
- Web/Job 차이는 각각 `MdcWebFilter`, `MdcJobFilter`에서 처리

## 권장 사용
신규 구성에서는 `logback-spring.xml`에서 `com.cube.opensearch.OpenSearchAppender`를 사용하세요.
기존 구성이 이미 `OpenSearchWebAppender` 또는 `OpenSearchJobAppender`를 사용 중이면 그대로 둬도 동작하도록 하위호환을 유지했습니다.
