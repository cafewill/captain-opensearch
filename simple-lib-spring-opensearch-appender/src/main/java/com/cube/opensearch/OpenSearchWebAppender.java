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

public class OpenSearchWebAppender extends AbstractOpenSearchAppender {
    @Override
    protected String getThreadName() {
        return "opensearch-web-log-sender";
    }

    public static class MdcWebFilter extends OncePerRequestFilter {
        private static final Logger ACCESS_LOG = LoggerFactory.getLogger(MdcWebFilter.class);

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String traceId = request.getHeader("X-Request-ID");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            MDC.put("trace_id", traceId);
            MDC.put("http_method", request.getMethod());
            MDC.put("http_path", request.getRequestURI());
            MDC.put("client_ip", resolveClientIp(request));
            response.setHeader("X-Request-ID", traceId);

            long startedAt = System.currentTimeMillis();
            try {
                filterChain.doFilter(request, response);
            } finally {
                MDC.put("http_status", String.valueOf(response.getStatus()));
                MDC.put("duration_ms", String.valueOf(System.currentTimeMillis() - startedAt));
                ACCESS_LOG.info("{} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
                MDC.clear();
            }
        }

        private String resolveClientIp(HttpServletRequest request) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
    }
}
