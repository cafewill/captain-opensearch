package com.cube.opensearch;

/**
 * 1.2.0 통합 Appender 본체.
 * Web/Job 구분 없이 공통 전송 로직은 이 클래스를 사용하고,
 * Web/Job 차이는 MDC 주입기에서만 분리한다.
 */
public class OpenSearchAppender extends AbstractOpenSearchAppender {
    @Override
    protected String getThreadName() {
        return "cube-opensearch-writer";
    }
}
