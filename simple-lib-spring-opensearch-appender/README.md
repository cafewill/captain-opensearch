# cube opensearch appender 리팩토링 초안

이번 초안은 아래 3가지를 실제 코드 구조로 반영했습니다.

1. **공통 코드 추출**
   - `AbstractOpenSearchAppender`
   - `BulkPayloadBuilder`
   - `OpenSearchSender`

2. **JSON 생성 라이브러리화**
   - `StringBuilder + esc()` 제거
   - Jackson `ObjectMapper` 기반으로 bulk action/document line 생성

3. **재시도 정책 추가**
   - exponential backoff
   - `maxRetries`
   - partial failure 재시도
   - dead-letter logger fallback

## SSL 정책
- 오빠 의견대로 **기업 내부망 전용 trust-all SSL 유지**했습니다.
- 대신 `setTrustAllSsl(boolean)`를 남겨 두어, 나중에 정책이 바뀌면 바로 끌 수 있게 했습니다.

## 호환성 포인트
- 기존 public 클래스명 유지
  - `OpenSearchWebAppender`
  - `OpenSearchJobAppender`
- 기존 주요 setter 유지
  - `url`, `index`, `username`, `password`, `app`, `env`, `maxBatchBytes`, `flushIntervalSeconds`, `queueSize`
- 신규 setter 추가
  - `connectTimeoutMillis`
  - `readTimeoutMillis`
  - `maxRetries`
  - `retryInitialDelayMillis`
  - `retryMaxDelayMillis`
  - `retryPartialFailures`
  - `deadLetterLoggerName`
  - `trustAllSsl`
  - `instanceId`

## 주의
이 폴더는 **리팩토링 소스 초안**입니다. 현재 대화 환경에서는 Spring / Logback / Jackson 전체 의존성을 받아 실제 컴파일까지 검증하지는 못했습니다. 그래서 바로 배포용 JAR 교체본이라기보다, **1차 리팩토링 베이스 코드**로 봐주시면 됩니다.

## 추천 다음 단계
1. 사내 실제 프로젝트에 이 소스를 넣고 컴파일 확인
2. logback XML 샘플 업데이트
3. 실패 응답 샘플로 partial retry 테스트
4. dead-letter logger를 파일 appender 또는 별도 인덱스로 연결
