package com.cube.simple.filter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        MDC.put("request_id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        MDC.put("http_method", request.getMethod());
        MDC.put("http_path",   request.getRequestURI());
        MDC.put("client_ip",   resolveClientIp(request));

        try {
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            MDC.put("duration_ms", String.valueOf(duration));
            MDC.put("http_status",  String.valueOf(response.getStatus()));
            log.info("{} {} → {} ({}ms)",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), duration);
            MDC.clear();
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
