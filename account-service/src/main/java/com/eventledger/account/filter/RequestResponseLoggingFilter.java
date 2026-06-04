package com.eventledger.account.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Logs every incoming HTTP request and outgoing response on Account Service.
 * Includes: method, URI, status, duration, traceId (propagated from Gateway).
 */
@Component
@Order(2)
@Slf4j
public class RequestResponseLoggingFilter implements Filter {

    private static final String[] SKIP_PATHS = {
            "/actuator", "/h2-console", "/swagger-ui", "/v3/api-docs"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        for (String skip : SKIP_PATHS) {
            if (uri.startsWith(skip)) {
                chain.doFilter(request, response);
                return;
            }
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(httpRequest);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(httpResponse);

        long startTime = Instant.now().toEpochMilli();

        // Get traceId from header first (propagated from Gateway), fallback to MDC
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = MDC.get("traceId");
        }

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long duration = Instant.now().toEpochMilli() - startTime;
            int status = wrappedResponse.getStatus();

            String requestBody = getBody(wrappedRequest.getContentAsByteArray());

            if (status >= 400) {
                log.warn("REQUEST  [{} {}] traceId={} body={}",
                        httpRequest.getMethod(), uri, traceId, requestBody);
                log.warn("RESPONSE [{} {}] status={} duration={}ms traceId={}",
                        httpRequest.getMethod(), uri, status, duration, traceId);
            } else {
                log.info("REQUEST  [{} {}] traceId={} body={}",
                        httpRequest.getMethod(), uri, traceId, requestBody);
                log.info("RESPONSE [{} {}] status={} duration={}ms traceId={}",
                        httpRequest.getMethod(), uri, status, duration, traceId);
            }

            wrappedResponse.copyBodyToResponse();
        }
    }

    private String getBody(byte[] content) {
        if (content == null || content.length == 0) return "";
        String body = new String(content, StandardCharsets.UTF_8);
        return body.length() > 500 ? body.substring(0, 500) + "...[truncated]" : body;
    }
}
