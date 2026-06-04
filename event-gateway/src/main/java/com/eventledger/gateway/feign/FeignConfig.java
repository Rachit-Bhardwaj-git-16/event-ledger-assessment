package com.eventledger.gateway.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class FeignConfig {

    @Bean
    public RequestInterceptor feignRequestInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                String traceId = MDC.get("traceId");
                if (traceId != null) {
                    template.header("X-Trace-Id", traceId);
                    log.debug("Propagating trace ID: {}", traceId);
                }
            }
        };
    }
}