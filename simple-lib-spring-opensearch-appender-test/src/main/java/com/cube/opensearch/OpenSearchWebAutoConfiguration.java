package com.cube.opensearch;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * REST 앱(servlet web)에서 MdcWebFilter 를 자동 등록하는 AutoConfiguration.
 *
 * 활성화 조건:
 *   - @ConditionalOnClass(name="OncePerRequestFilter") : spring-webmvc 없는 Job 앱에서는 비활성
 *     (클래스명 문자열로 참조 → Job 앱에서 OpenSearchWebAppender 클래스 로딩 자체를 차단)
 *   - @ConditionalOnWebApplication(SERVLET) : 서블릿 기반 웹 앱에서만 활성
 *   - @ConditionalOnMissingBean(MdcWebFilter) : 앱이 직접 등록한 경우 비활성
 *
 * REST 앱의 logback-spring.xml 에 class="com.cube.opensearch.OpenSearchWebAppender" 를 선언하면
 * 이 AutoConfiguration 이 MdcWebFilter(OncePerRequestFilter)를 자동 등록하여
 * 모든 HTTP 요청에 trace_id · http_method · http_path · client_ip MDC 를 주입하고
 * 응답 완료 시 http_status · duration_ms 를 포함한 access log 를 발행한다.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnMissingBean(OpenSearchWebAppender.MdcWebFilter.class)
@Import(OpenSearchWebAppender.MdcWebFilter.class)
public class OpenSearchWebAutoConfiguration {
}
