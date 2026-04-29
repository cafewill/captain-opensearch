# JASYPT 암복호화 구현 가이드

> 대상: `simple-jobs-spring-maven` 계열 6개 프로젝트  
> Spring Boot 2.7.x (Java 8/11) · Spring Boot 3.5.x (Java 17/21/25)

---

## 1. 구현 배경 및 핵심 문제

### 왜 단순 jasypt-spring-boot만으로는 부족한가

`application.properties`에 `ENC(...)` 형식으로 암호화된 비밀번호를 넣으면,  
Jasypt가 이 값을 복호화해서 `Environment`에 반영하는 시점은  
**Spring Context Refresh 단계** (BeanFactoryPostProcessor) 이다.

그런데 `logback-spring.xml`은 **이보다 훨씬 이른** `ApplicationEnvironmentPreparedEvent` 시점에  
`LoggingApplicationListener` (order: `HIGHEST_PRECEDENCE + 20`)에 의해 초기화된다.

```
애플리케이션 시작 타임라인
├─ ApplicationEnvironmentPreparedEvent  ←── logback-spring.xml 초기화 여기서 발생
│   ├─ EnvironmentPostProcessors        (order: +10)
│   ├─ OpenSearchPasswordBridge         (order: +15)  ← 커스텀 브릿지
│   └─ LoggingApplicationListener       (order: +20)  ← logback 초기화
│
└─ Context Refresh
    └─ BeanFactoryPostProcessor          ←── jasypt 복호화 여기서 발생 (이미 늦음)
```

**결론**: logback이 `opensearch.password`를 읽을 때 Jasypt는 아직 복호화하지 않은 상태이므로  
`ENC(AinSz01jh1I4dztG7DjklXvzX5J6+HG0)` 원문이 그대로 logback에 전달된다 → 401 Unauthorized 발생.

---

## 2. 해결책: OpenSearchPasswordBridge

logback 초기화 직전에 수동으로 복호화하여 JVM 시스템 프로퍼티로 전달하는 브릿지 리스너를 구현.

### 파일 위치

```
src/main/java/com/cube/simple/OpenSearchPasswordBridge.java
```

### 전체 코드

```java
package com.cube.simple;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.NoIvGenerator;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class OpenSearchPasswordBridge
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    @Override
    public int getOrder() {
        // EnvironmentPostProcessors(+10) 이후, LoggingApplicationListener(+20) 이전
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        try {
            ConfigurableEnvironment env = event.getEnvironment();
            String rawPassword = env.getProperty("opensearch.password", "");

            if (rawPassword.startsWith("ENC(") && rawPassword.endsWith(")")) {
                String encKey = resolveEncryptorKey(env);
                if (encKey != null && !encKey.isEmpty()) {
                    String algorithm = env.getProperty("jasypt.encryptor.algorithm", "PBEWithMD5AndDES");
                    String ivGenClass = env.getProperty("jasypt.encryptor.iv-generator-classname", "");
                    String cipherText = rawPassword.substring(4, rawPassword.length() - 1);

                    StandardPBEStringEncryptor enc = new StandardPBEStringEncryptor();
                    enc.setPassword(encKey);
                    enc.setAlgorithm(algorithm);
                    if ("org.jasypt.iv.NoIvGenerator".equals(ivGenClass)) {
                        enc.setIvGenerator(new NoIvGenerator());
                    }
                    rawPassword = enc.decrypt(cipherText);
                }
            }

            System.setProperty("OPENSEARCH_PASS_RESOLVED", rawPassword);
        } catch (Exception ignored) {
        }
    }

    private String resolveEncryptorKey(ConfigurableEnvironment env) {
        String key = env.getProperty("jasypt.encryptor.password", "");
        if (key.isEmpty()) {
            String envKey = System.getenv("JASYPT_ENCRYPTOR_PASSWORD");
            if (envKey != null && !envKey.isEmpty()) {
                key = envKey;
            }
        }
        return key;
    }
}
```

### 리스너 등록 방법 (2가지 중 택1)

#### 방법 A: spring.factories 자동 등록 (권장)

`SimpleApplication.java`를 수정하지 않고 팩토리 파일만 추가한다.  
Spring Boot 버전 무관하게 **동일한 파일명과 형식**을 사용한다.

```
src/main/resources/META-INF/spring.factories
```
```properties
org.springframework.context.ApplicationListener=\
  com.example.OpenSearchPasswordBridge
```

> **Spring Boot 3.x 주의**: `META-INF/spring/org.springframework.context.ApplicationListener.imports` 파일은  
> `AutoConfiguration` 전용 포맷이라 `ApplicationListener` 등록에 **적용되지 않는다**.  
> `spring.factories` 방식은 Spring Boot 2.x / 3.x 모두 동작한다.

`SimpleApplication.java`는 기본 형태 그대로 유지:

```java
public static void main(String[] args) {
    SpringApplication.run(SimpleApplication.class, args);
}
```

#### 방법 B: main 메서드 직접 등록

```java
public static void main(String[] args) {
    SpringApplication app = new SpringApplication(SimpleApplication.class);
    app.addListeners(new OpenSearchPasswordBridge());
    app.run(args);
}
```

---

## 3. 설정 파일 구성

### pom.xml — jasypt 의존성

| Spring Boot 버전 | Java 버전 | jasypt 버전 |
|:---|:---|:---|
| 2.7.x | Java 8, 11 | `3.0.3` |
| 3.5.x | Java 17, 21, 25 | `3.0.5` |

```xml
<!-- Spring Boot 2.7.x (Java 8/11) -->
<dependency>
    <groupId>com.github.ulisesbocchio</groupId>
    <artifactId>jasypt-spring-boot-starter</artifactId>
    <version>3.0.3</version>
</dependency>

<!-- Spring Boot 3.5.x (Java 17/21/25) -->
<dependency>
    <groupId>com.github.ulisesbocchio</groupId>
    <artifactId>jasypt-spring-boot-starter</artifactId>
    <version>3.0.5</version>
</dependency>
```

### application.properties

```properties
# OpenSearch 비밀번호: Jasypt ENC() 형식으로 암호화 필수
opensearch.password=ENC(AinSz01jh1I4dztG7DjklXvzX5J6+HG0)

# Jasypt 설정 (로컬/개발용 - 운영은 환경변수 권장)
jasypt.encryptor.password=captainkey
jasypt.encryptor.algorithm=PBEWithMD5AndDES
jasypt.encryptor.iv-generator-classname=org.jasypt.iv.NoIvGenerator
```

### logback-spring.xml — 비밀번호 프로퍼티 선언

```xml
<!-- springProperty 대신 JVM 시스템 프로퍼티로 읽어야 함 (타이밍 문제) -->
<property name="OPENSEARCH_PASS" value="${OPENSEARCH_PASS_RESOLVED:-}" />
```

> **주의**: `<springProperty source="opensearch.password"/>` 로 쓰면 `ENC(...)` 원문이 그대로 전달되므로 반드시 위 방식을 사용한다.

---

## 4. 암호화 값 생성 방법

### 방법 A: jasypt CLI (권장)

```bash
# 암호화
java -cp jasypt-1.9.3.jar \
  org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input="평문비밀번호" \
  password=captainkey \
  algorithm=PBEWithMD5AndDES

# 복호화 검증
java -cp jasypt-1.9.3.jar \
  org.jasypt.intf.cli.JasyptPBEStringDecryptionCLI \
  input="AinSz01jh1I4dztG7DjklXvzX5J6+HG0" \
  password=captainkey \
  algorithm=PBEWithMD5AndDES
```

### 방법 B: Maven 플러그인

```bash
mvn jasypt:encrypt-value \
  -Djasypt.encryptor.password=captainkey \
  -Djasypt.plugin.value="평문비밀번호"
```

---

## 5. 운영 환경 키 관리

로컬 개발에서는 `jasypt.encryptor.password=captainkey`를 `application.properties`에 넣어도 되지만,  
**운영 환경에서는 절대 파일에 키를 포함하지 말고 환경변수로 관리**한다.

```bash
# OS 환경변수 설정 (운영 서버 / Docker / K8s Secret)
export JASYPT_ENCRYPTOR_PASSWORD=운영용_강력한_키

# Docker Compose
environment:
  - JASYPT_ENCRYPTOR_PASSWORD=운영용_강력한_키

# K8s Secret 예시
env:
  - name: JASYPT_ENCRYPTOR_PASSWORD
    valueFrom:
      secretKeyRef:
        name: app-secrets
        key: jasypt-key
```

`OpenSearchPasswordBridge`는 `jasypt.encryptor.password` 프로퍼티를 먼저 확인하고,  
없으면 `JASYPT_ENCRYPTOR_PASSWORD` 환경변수를 폴백으로 사용한다.

---

## 6. 타 프로젝트 적용 체크리스트

새 Spring Boot 프로젝트에 동일 패턴을 적용할 때:

- [ ] `pom.xml` — jasypt-spring-boot-starter 의존성 추가 (버전은 Spring Boot 버전에 맞게)
- [ ] `OpenSearchPasswordBridge.java` 복사 → 패키지명 수정
- [ ] `META-INF/spring.factories` 생성 — `ApplicationListener` 등록 (방법 A, 권장)  
  또는 `SimpleApplication.java` — `app.addListeners(new OpenSearchPasswordBridge())` 추가 (방법 B)
- [ ] `logback-spring.xml` — `<property name="OPENSEARCH_PASS" value="${OPENSEARCH_PASS_RESOLVED:-}" />` 형식으로 변경
- [ ] `application.properties` — 비밀번호를 `ENC(...)` 형식으로 변경, Jasypt 설정 3줄 추가
- [ ] 로컬 실행 후 logback 초기화 로그에서 `"Demo3543##" substituted for "${OPENSEARCH_PASS_RESOLVED:-}"` 확인 (Spring Boot 3.x)
- [ ] OpenSearch 인덱스에 로그 정상 적재 및 401 오류 없음 확인

---

## 7. 검증 결과 (2026-04-29)

| 프로젝트 | Java | Spring Boot | jasypt | 복호화 | OpenSearch 수신 | 401 오류 |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| simple-jobs-spring-maven | 21 | 3.5.13 | 3.0.5 | ✅ | ✅ (5,698건) | 없음 |
| with-java8 | 8 | 2.7.18 | 3.0.3 | ✅ | ✅ (783건) | 없음 |
| with-java11 | 11 | 2.7.18 | 3.0.3 | ✅ | ✅ (781건) | 없음 |
| with-java17 | 17 | 3.5.13 | 3.0.5 | ✅ | ✅ (781건) | 없음 |
| with-java21 | 21 | 3.5.13 | 3.0.5 | ✅ | ✅ (1,628건) | 없음 |
| with-java25 | 25 | 3.5.13 | 3.0.5 | ✅ | ✅ (66건) | 없음 |

OpenSearch 3.6.0 single-node (docker-compose-opensearch-3.6.0.yml) 환경에서 전 프로젝트 정상 확인.
