# Engineering MDC Example

`simple-jobs-spring-maven-with-mdc`, `simple-jobs-spring-gradle-with-mdc` 에 연구용 MDC 샘플을 확장했다.

이번 반영의 핵심:
- 로보틱스 공학 연구소 시나리오용 MDC 필드 추가
- 자동차 공학 검증 시나리오용 MDC 필드 추가
- 두 도메인 로직을 `ScheduleService` 에 직접 두지 않고 신규 서비스 코드로 분리

신규 서비스:
- `RoboticsResearchMdcService`
- `AutomotiveEngineeringMdcService`

## 추가된 MDC 필드

공통 필드:
- `traceId`
- `jobName`
- `jobRole`
- `framework`
- `success`
- `elapsed_ms`

로보틱스 공학 연구소 필드:
- `labDomain=robotics-research`
- `researchLab`
- `robotCell`
- `robotUnitId`
- `processStage`
- `researchProgram`
- `sampleType`
- `safetyMode`
- `digitalTwinVersion`
- `operatorShift`
- `experimentId`

자동차 공학 필드:
- `engineeringDomain=automotive-engineering`
- `vehicleTestStage`
- `vehiclePlatform`
- `testFacility`
- `controllerRelease`
- `validationMode`
- `componentVariant`
- `driveCycleProfile`
- `vehicleExperimentId`

## 서비스 분리 구조

`ScheduleService` 역할:
- 스케줄 실행
- 공통 MDC 필드 입력
- 로보틱스/자동차 서비스가 만든 MDC 맵 merge
- 최종 로그 출력

`RoboticsResearchMdcService` 역할:
- `system`, `manager`, `operator` 작업별 로보틱스 연구 시나리오 매핑
- Dashboards 필터링에 적합한 연구소/셀/프로세스 필드 생성

`AutomotiveEngineeringMdcService` 역할:
- 동일 작업에 대한 자동차 공학 검증 시나리오 매핑
- 차량 플랫폼, 시험 단계, 시험 설비, 제어기 릴리스 정보 생성

## 프로세스 시나리오

### 로보틱스

| jobName | processStage | 연구 맥락 |
|---|---|---|
| `system` | `sensor-calibration` | 비전/라이다 센서 보정 |
| `manager` | `trajectory-planning` | 다중 로봇 경로 계획 |
| `operator` | `gripper-validation` | 엔드이펙터 검증 |

### 자동차 공학

| jobName | vehicleTestStage | 검증 맥락 |
|---|---|---|
| `system` | `battery-thermal-cycle` | 배터리 열 사이클 검증 |
| `manager` | `adas-scenario-replay` | ADAS 시나리오 재생 |
| `operator` | `chassis-vibration-check` | 차체 진동/NVH 점검 |

## 실행 방법

### Maven with MDC 샘플

```bash
cd simple-jobs-spring-maven-with-mdc
cp src/main/resources/application-example.properties src/main/resources/application.properties
./mvnw spring-boot:run
```

### Gradle with MDC 샘플

```bash
cd simple-jobs-spring-gradle-with-mdc
cp src/main/resources/application-example.properties src/main/resources/application.properties
./gradlew bootRun
```

OpenSearch / Dashboards 실행:

```bash
docker compose -f docker-compose-3.6.0.yml up -d
```

## Discover 확인

Dashboards 접속:

```text
http://localhost:5601
```

data view 패턴:

```text
logs-simple-jobs-spring-*-with-mdc-*
```

time field:

```text
@timestamp
```

Discover 컬럼 추천:

```text
processStage
researchLab
robotCell
vehicleTestStage
vehiclePlatform
testFacility
controllerRelease
elapsed_ms
success
```

추천 검색:

```text
labDomain:"robotics-research"
```

```text
engineeringDomain:"automotive-engineering"
```

```text
processStage:"trajectory-planning"
```

```text
vehicleTestStage:"adas-scenario-replay"
```

## Dashboards 시각화 예시

### 1. 로보틱스 프로세스 단계별 실행 건수

- Visualization: Lens bar
- X-axis: Terms `processStage.keyword`
- Y-axis: Count
- 의미: 로봇 연구 파이프라인의 실행 빈도 확인

### 2. 로보틱스 연구소/셀 분포

- Visualization: Data table
- Buckets:
  - Terms `researchLab.keyword`
  - Terms `robotCell.keyword`
- Metric: Count
- 의미: 어떤 연구소와 셀에서 로그가 발생하는지 확인

### 3. 자동차 시험 단계 분포

- Visualization: Donut
- Slice: Terms `vehicleTestStage.keyword`
- Metric: Count
- 의미: 배터리, ADAS, NVH 관련 시험 분포 확인

### 4. 차량 플랫폼별 시험 현황

- Visualization: Bar chart
- X-axis: Terms `vehiclePlatform.keyword`
- Split series: Terms `validationMode.keyword`
- Y-axis: Count
- 의미: 어떤 플랫폼이 어떤 검증 모드로 많이 실행되는지 확인

### 5. 평균 소요 시간

- Visualization: Metric 또는 Bar chart
- Metric: Average `elapsed_ms`
- Split terms:
  - `processStage.keyword`
  - 또는 `vehicleTestStage.keyword`
- 의미: 연구/시험 단계별 소요 시간 비교

### 6. 통합 엔지니어링 모니터

- Visualization: Line chart
- X-axis: Date histogram `@timestamp`
- Split series:
  - `processStage.keyword`
  - 또는 `vehicleTestStage.keyword`
- 의미: 시간 흐름에 따라 로보틱스와 자동차 검증 이벤트를 함께 추적

## 대시보드 구성 추천

대시보드 이름 예시:

```text
Engineering Research Process Monitor
```

위젯 배치 추천:
- 상단 왼쪽: 로보틱스 프로세스 단계별 실행 건수
- 상단 오른쪽: 자동차 시험 단계 분포
- 중단 왼쪽: 연구소/셀 분포
- 중단 오른쪽: 차량 플랫폼별 시험 현황
- 하단 전체폭: 통합 엔지니어링 타임라인

## 운영 팁

- `processStage`, `vehicleTestStage` 는 Terms 기반 시각화에 바로 적합하다.
- `researchLab`, `vehiclePlatform`, `validationMode` 는 필터 pill 로 드릴다운하기 좋다.
- `experimentId`, `vehicleExperimentId` 는 개별 실험 추적용 키로 쓰기 좋다.
- `elapsed_ms` 는 숫자 필드이므로 평균, 최대, percentile 집계에 바로 사용할 수 있다.
- 실패 시나리오가 필요하면 `success=false`, `failureCode`, `failureDomain` 같은 MDC 를 같은 방식으로 확장하면 된다.
