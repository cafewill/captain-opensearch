package com.cube.opensearch;

/**
 * logstash-logback-encoder 의 StructuredArguments.keyValue() 로 전달된
 * ObjectAppendingMarker 를 OpenSearch 문서 필드로 직렬화하는 Appender.
 *
 * 사용 조건: net.logstash.logback:logstash-logback-encoder 가 클래스패스에 있어야 한다.
 * 없으면 StructuredArgs 필드만 무시되고 나머지 로그는 정상 전송된다.
 *
 * logback-spring.xml 설정 예시:
 * <appender name="OPENSEARCH" class="com.cube.opensearch.StructuredArgsOpenSearchAppender">
 *   <url>https://localhost:9200</url>
 *   <index>logs-app-{date}</index>
 *   <keyPrefix>arg_</keyPrefix>
 *   <objectSerialization>true</objectSerialization>
 * </appender>
 */
public class StructuredArgsOpenSearchAppender extends OpenSearchAppender {

    public StructuredArgsOpenSearchAppender() {
        setIncludeStructuredArgs(true);
    }
}
