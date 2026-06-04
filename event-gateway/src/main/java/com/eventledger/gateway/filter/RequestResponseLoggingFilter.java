package com.eventledger.gateway.filter;

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
 * Logs every incoming HTTP request and outgoing response.
 * Includes: method, URI, status, duration, traceId, request body, response body.
 * Runs AFTER TraceFilter (Order 2) so traceId is already in MDC.
 */
@Component
@Order(2)
@Slf4j
public class RequestResponseLoggingFilter implements Filter {

    // Skip logging for noisy actuator/health/h2-console endpoints
    private static final String[] SKIP_PATHS = {
        "/actuator", "/h2-console", "/swagger-ui", "/v3/api-docs"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip noisy internal paths
        String uri = httpRequest.getRequestURI();
        for (String skip : SKIP_PATHS) {
            if (uri.startsWith(skip)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // Wrap request and response so we can read body without consuming the stream
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(httpRequest);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(httpResponse);

        long startTime = Instant.now().toEpochMilli();
        String traceId = MDC.get("traceId");

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long duration = Instant.now().toEpochMilli() - startTime;
            int status = wrappedResponse.getStatus();

            String requestBody = getBody(wrappedRequest.getContentAsByteArray());
            String responseBody = getBody(wrappedResponse.getContentAsByteArray());

            if (status >= 400) {
                log.warn("REQUEST  [{} {}] traceId={} body={}",
                        httpRequest.getMethod(), uri, traceId, requestBody);
                log.warn("RESPONSE [{} {}] status={} duration={}ms traceId={} body={}",
                        httpRequest.getMethod(), uri, status, duration, traceId, responseBody);
            } else {
                log.info("REQUEST  [{} {}] traceId={} body={}",
                        httpRequest.getMethod(), uri, traceId, requestBody);
                log.info("RESPONSE [{} {}] status={} duration={}ms traceId={}",
                        httpRequest.getMethod(), uri, status, duration, traceId);
            }

            // MUST copy body back — otherwise client gets empty response
            wrappedResponse.copyBodyToResponse();
        }
    }

    private String getBody(byte[] content) {
        if (content == null || content.length == 0) return "";
        String body = new String(content, StandardCharsets.UTF_8);
        // Truncate very large bodies to avoid log bloat
        return body.length() > 500 ? body.substring(0, 500) + "...[truncated]" : body;
    }
}
