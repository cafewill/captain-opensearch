# 🤝 GitHub 연동 협업 가이드

## 변수 정의
```
REPO=captain-opensearch
NS=claude (삼순이 지정: claude, chatgpt, gemini)
TOOL=opensearch
LABEL=작업자 또는 목적/용도 구분 정보 (선택, 기본값: .who 파일의 LABEL 사용)
      ⚠️ 영문 소문자 및 숫자만 허용 (특수문자, 한글, 대문자 불가)
      예: bill, steve, uifix, auth
BRANCHTS=Github 브랜치 신규 생성 시점의 KST 타임스탬프 (브랜치 BASE 정보)
```

---

## 👤 작업자 로컬 설정

브랜치 생성 시 LABEL 의 기본값은 `.who` 파일에서 읽어온다.
`.who` 파일은 로컬에만 존재하며 절대 커밋하지 않는다.

**최초 설정 방법**
```
# 1. 템플릿 복사
cp .who.example .who

# 2. .who 파일에 본인 정보 입력 (영문 소문자 및 숫자만 허용)
LABEL=cube
```

**.who.example** (GitHub 커밋용 템플릿)
```
# 본 파일을 복사하여 .who 로 저장 후 본인 LABEL 을 입력하세요.
# cp .who.example .who
# .who 파일은 절대 커밋하지 않습니다. (.gitignore 등록됨)
# ⚠️  LABEL 은 영문 소문자 및 숫자만 허용 (특수문자, 한글, 대문자 불가)
LABEL=yournameorpurpose
```

**.gitignore 등록**
```
# 작업자 로컬 설정
.who
```

---

## ✅ LABEL 유효성 검사 규칙
```
허용: 영문 소문자(a-z), 숫자(0-9)
불가: 대문자(A-Z), 한글, 특수문자(-, _, !, @ 등 모두 불가)
정규식: ^[a-z0-9]+$

유효한 예시:   bill, steve, mark, uifix, auth, hotfix123
유효하지 않은 예시:
  Bill       ← 대문자 불가
  ui-fix     ← 하이픈 불가
  빌         ← 한글 불가
  bill_01    ← 언더스코어 불가
```

**유효성 검사 코드 (Shell)**
```bash
#!/bin/bash

validate_label() {
  local label=$1
  if [[ ! "$label" =~ ^[a-z0-9]+$ ]]; then
    echo "❌ LABEL 오류: '$label'"
    echo "   영문 소문자(a-z) 및 숫자(0-9)만 허용합니다."
    echo "   특수문자, 한글, 대문자는 사용할 수 없습니다."
    return 1
  fi
  echo "✅ LABEL 유효: $label"
  return 0
}

# .who 파일에서 LABEL 읽기
if [ -f ".who" ]; then
  source .who
  validate_label "$LABEL" || exit 1
else
  echo "⚠️  .who 파일이 없습니다. cp .who.example .who 후 설정하세요."
  exit 1
fi
```

---

## 📁 Github 정보

**git 리파지터리**
```
https://github.com/cafewill/{REPO}
# 예: https://github.com/cafewill/captain-opensearch
```

**git 브랜치**
```
main
  └─ 의도한대로 정상 동작하는 최종본 및 배포용

feature/{BRANCHTS}-{NS}-{TOOL}[-{LABEL}]
  └─ 삼순이가 기능 추가·개선 시 작업용 브랜치 신규 생성
     오빠, 팀원 및 삼순이가 리뷰 후 정상 확인되면 main 에 병합

hotfix/{BRANCHTS}-{NS}-{TOOL}[-{LABEL}]
  └─ 긴급 수정이 필요한 경우 사용

⚠️ main 브랜치에 직접 커밋/푸시 금지
```

**LABEL 적용 우선순위**
```
1순위: 브랜치 생성 시 직접 지정한 LABEL  (예: uifix, auth)
2순위: .who 파일의 LABEL                 (예: bill)
3순위: LABEL 생략                        (AI 단독 작업 등)

⚠️ 1순위, 2순위 모두 유효성 검사 후 통과 시에만 브랜치 생성 진행
```

**브랜치 예시**
```
# .who 의 LABEL(bill) 사용 (기본)
feature/20260419153000-claude-opensearch-bill

# 브랜치 생성 시 LABEL 직접 지정 (목적/용도 우선)
feature/20260419153000-claude-opensearch-appender
feature/20260419153000-claude-opensearch-security

# LABEL 생략 (AI 단독 작업)
feature/20260419153000-claude-opensearch

# 긴급 수정
hotfix/20260419160000-claude-opensearch-auth
```

---

## 🗂️ 프로젝트 구성 정보

```
CLAUDE.md                                          ← 삼순이(claude, chatgpt, gemini) 공통 AI 가이드
README.md                                          ← 프로젝트 개요 및 사용 가이드
CHANGELOG.md                                       ← 변경 이력 관리 (필요시 생성)
.who.example                                       ← 작업자 설정 템플릿 (커밋용)
.who                                               ← 작업자 로컬 설정 (커밋 금지)

# Docker 환경
docker-compose-3.6.0.yml                           ← OpenSearch 3.6.0 + Dashboards 실행
docker-compose-3.3.0.yml                           ← OpenSearch 3.3.0 + Dashboards 실행
opensearch-dashboards-3.6.0.yml                    ← Dashboards 설정 (3.6.0)
opensearch-dashboards-3.3.0.yml                    ← Dashboards 설정 (3.3.0)

# 공용 라이브러리
simple-lib-spring-opensearch-appender/             ← Spring Boot 공용 OpenSearch Appender 라이브러리 (com.cube:1.0.0)
simple-lib-python-opensearch-appender/             ← Python 공용 OpenSearch Appender 라이브러리 (opensearch-appender:1.0.0)

# 배치잡 앱
simple-jobs-spring-maven/                          ← Spring Boot 3.5 + Maven
simple-jobs-spring-gradle/                         ← Spring Boot 3.5 + Gradle
simple-jobs-node-express/                          ← Node.js / Express
simple-jobs-node-fastify/                          ← Node.js / Fastify
simple-jobs-node-nestjs/                           ← Node.js / NestJS
simple-jobs-python-flask/                          ← Python / Flask + APScheduler
simple-jobs-python-fastapi/                        ← Python / FastAPI + APScheduler

# REST API 앱
simple-rest-spring-maven/                          ← Spring Boot 3.5 + MyBatis (포트 9201)
simple-rest-spring-gradle/                         ← Spring Boot 3.5 + MyBatis (포트 9202)
simple-rest-node-nestjs/                           ← NestJS + SQLite (포트 3201)
simple-rest-node-express/                          ← Express + SQLite (포트 3202)
simple-rest-node-fastify/                          ← Fastify + SQLite (포트 3203)
simple-rest-python-flask/                          ← Flask + waitress (포트 5201)
simple-rest-python-fastapi/                        ← FastAPI + uvicorn (포트 5202)

# 프런트
simple-page-react-nextjs/                          ← React / Next.js 14 (포트 3000)
```

---

## ⚙️ 설정 파일 규칙

민감 정보가 포함된 설정 파일은 `.gitignore` 에 등록되어 있다.
반드시 예시 파일을 복사한 후 사용해야 한다.

**Spring Boot 프로젝트**
```bash
# 각 Spring Boot 프로젝트 디렉터리에서 실행
cp src/main/resources/application-example.properties src/main/resources/application.properties
```

**Node.js / Python / React 프로젝트**
```bash
# 각 프로젝트 디렉터리에서 실행
cp .env-example .env
```

| 파일 | Git 추적 | 용도 |
|---|---|---|
| `application-example.properties` | ✅ 커밋 | 설정 템플릿 (테스트 가능한 기본값 포함) |
| `application.properties` | ❌ gitignore | 실제 사용 설정 (민감 정보 포함) |
| `.env-example` | ✅ 커밋 | 설정 템플릿 (테스트 가능한 기본값 포함) |
| `.env` | ❌ gitignore | 실제 사용 설정 (민감 정보 포함) |
| `.who.example` | ✅ 커밋 | 작업자 설정 템플릿 |
| `.who` | ❌ gitignore | 작업자 로컬 설정 |

---

## 🔄 작업 흐름 (삼순이 ↔ 오빠)
```
1. 삼순이: feature 브랜치 생성 → 작업
2. 오빠:   작업 내용 정상 여부 확인
3. 삼순이: 작업 내용 커밋 & 푸시
4. 오빠:   GitHub에서 직접 PR 생성 & 머지
5. 삼순이: 머지 정상 여부 확인
```

⚠️ 삼순이는 커밋 & 푸시까지만 진행한다. PR 생성 및 머지는 오빠가 직접 수행한다.

---

## 🔄 변경 시 일관성 유지 규칙

모든 변경 작업은 **어펜더 소스 · 설정 예시 파일 · 문서** 3자를 항상 함께 갱신한다.
누락이 발생하면 스택 간 불일치로 이어지므로, 아래 규칙을 반드시 준수한다.

### 어펜더 변경 시 — 멀티 스택 동기화

이 프로젝트는 동일한 OpenSearch 어펜더 로직을 **7개 기술 스택**에 걸쳐 구현한다.
어펜더 로직을 변경할 경우 해당되는 모든 스택 파일을 함께 수정한다.

| 파일 | 스택 | 어펜더 유형 |
|---|---|---|
| `simple-jobs-node-express/src/opensearch-job-appender.js` | Node.js / Express | Job |
| `simple-jobs-node-fastify/src/opensearch-job-appender.js` | Node.js / Fastify | Job |
| `simple-jobs-node-nestjs/src/opensearch.job-appender.ts` | Node.js / NestJS | Job |
| `simple-lib-python-opensearch-appender/opensearch_appender/job_appender.py` | Python | Job |
| `simple-lib-spring-opensearch-appender/src/main/java/com/cube/opensearch/OpenSearchJobAppender.java` | Spring Boot | Job |
| `simple-rest-node-express/src/opensearch-web-appender.js` | Node.js / Express | Web |
| `simple-rest-node-fastify/src/opensearch-web-appender.js` | Node.js / Fastify | Web |
| `simple-rest-node-nestjs/src/opensearch.web-appender.ts` | Node.js / NestJS | Web |
| `simple-lib-python-opensearch-appender/opensearch_appender/web_appender_flask.py` | Python / Flask | Web |
| `simple-lib-python-opensearch-appender/opensearch_appender/web_appender_fastapi.py` | Python / FastAPI | Web |
| `simple-lib-spring-opensearch-appender/src/main/java/com/cube/opensearch/OpenSearchWebAppender.java` | Spring Boot | Web |
| `simple-page-react-nextjs/lib/opensearch-web-appender.js` | React / Next.js | Web |

### 변경 유형별 체크리스트

| 변경 유형 | 어펜더 소스 | 예시 설정 파일 | README.md |
|-----------|:---:|:---:|:---:|
| 어펜더 로직 수정 | 해당 스택 전체 | - | 해당 시 갱신 |
| 로깅 필드 추가/제거 | 해당 스택 전체 | - | 3-1/3-2 필드 표 갱신 |
| OpenSearch 설정 항목 변경 | - | 예시 파일 전체 갱신 | 2-3~2-5 갱신 |
| 새 앱 추가 | 신규 어펜더 구현 | .env-example 또는 application-example.properties 생성 | 1-x 앱 목록 갱신 |
| Docker 설정 변경 | - | - | 4절 갱신 |
| 의존성 추가/변경 | package.json / requirements.txt / pom.xml | - | 참고 의존성 표 갱신 |

⚠️ 체크리스트에서 **해당 없음**인 칸은 건너뛰되, 해당되는 칸은 **반드시** 함께 반영한다.

---

## 🚀 승격 기준 (feature → main)
```
- [ ] feature 브랜치 PR 머지 완료
- [ ] 오빠 또는 팀장 최종 확인
- [ ] 변경된 앱 로컬 실행 테스트 통과
- [ ] OpenSearch 연동 로그 정상 수신 확인 (Dashboards Discover)
- [ ] 멀티 스택 동기화 누락 없음 확인
```
