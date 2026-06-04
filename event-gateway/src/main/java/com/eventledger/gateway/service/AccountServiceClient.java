package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.TransactionDto;
import com.eventledger.gateway.feign.AccountFeignClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class AccountServiceClient {

    private final AccountFeignClient accountFeignClient;

    public AccountServiceClient(AccountFeignClient accountFeignClient) {
        this.accountFeignClient = accountFeignClient;
    }

    @CircuitBreaker(
        name = "account-service",
        fallbackMethod = "recordTransactionFallback"
    )
    @Retry(
        name = "account-service"
    )
    public void recordTransaction(EventRequest eventRequest, String traceId) {
        log.info("Recording transaction for account: {} with event: {}",
            eventRequest.getAccountId(), eventRequest.getEventId());

        TransactionDto transaction = TransactionDto.builder()
            .eventId(eventRequest.getEventId())
            .type(eventRequest.getType())
            .amount(eventRequest.getAmount())
            .currency(eventRequest.getCurrency())
            .eventTimestamp(eventRequest.getEventTimestamp())
            .build();

        accountFeignClient.createTransaction(
            eventRequest.getAccountId(),
            transaction,
            traceId
        );

        log.info("Transaction recorded successfully for account: {}", eventRequest.getAccountId());
    }

    /**
     * Fallback: circuit breaker open hone par 503 throw karo.
     * Event gateway mein save nahi hoga jab account service down ho.
     */
    public void recordTransactionFallback(EventRequest eventRequest, String traceId, Exception ex) {
        log.warn("Circuit breaker fallback triggered for account: {}. Error: {}",
            eventRequest.getAccountId(), ex.getMessage());
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Account Service is currently unavailable. Please try again later."
        );
    }
}
