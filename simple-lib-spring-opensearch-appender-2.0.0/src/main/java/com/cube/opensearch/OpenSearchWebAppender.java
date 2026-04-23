package com.cube.opensearch;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 하위호환용 래퍼.
 * 1.2.0부터는 OpenSearchAppender 사용을 권장하고,
 * Web 차이는 MdcWebFilter에서만 처리한다.
 */
@Deprecated
public class OpenSearchWebAppender extends OpenSearchAppender {

    public static class MdcWebFilter extends OncePerRequestFilter {
        private static final Logger ACCESS_LOG = LoggerFactory.getLogger(MdcWebFilter.class);

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String traceId = resolveTraceId(request);
            long start = System.currentTimeMillis();
            try {
                MDC.put("trace_id", traceId);
                MDC.put("http_method", request.getMethod());
                MDC.put("http_path", request.getRequestURI());
                MDC.put("client_ip", resolveClientIp(request));
                response.setHeader("X-Request-ID", traceId);
                filterChain.doFilter(request, response);
            } finally {
                MDC.put("http_status", String.valueOf(response.getStatus()));
                MDC.put("duration_ms", String.valueOf(System.currentTimeMillis() - start));
                ACCESS_LOG.info("{} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
                MDC.clear();
            }
        }

        private String resolveTraceId(HttpServletRequest request) {
            String traceId = firstHeader(request, "X-Request-ID", "X-Trace-Id", "X-B3-TraceId", "traceparent");
            return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        }

        private String resolveClientIp(HttpServletRequest request) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }

        private String firstHeader(HttpServletRequest request, String... names) {
            for (String name : names) {
                String value = request.getHeader(name);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }
}
