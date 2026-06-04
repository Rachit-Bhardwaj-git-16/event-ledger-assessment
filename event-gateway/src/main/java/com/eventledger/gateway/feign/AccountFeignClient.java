package com.eventledger.gateway.feign;

import com.eventledger.gateway.dto.TransactionDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@FeignClient(
    name = "account-service",
    url = "${account-service.url:http://localhost:8081}",
    configuration = FeignConfig.class
)
public interface AccountFeignClient {

    @PostMapping("/accounts/{accountId}/transactions")
    TransactionResponse createTransaction(
        @PathVariable("accountId") String accountId,
        @RequestBody TransactionDto transaction,
        @RequestHeader("X-Trace-Id") String traceId
    );

    @GetMapping("/accounts/{accountId}/balance")
    BalanceResponse getBalance(
        @PathVariable("accountId") String accountId,
        @RequestHeader("X-Trace-Id") String traceId
    );

    @GetMapping("/accounts/{accountId}")
    AccountResponse getAccount(
        @PathVariable("accountId") String accountId,
        @RequestHeader("X-Trace-Id") String traceId
    );

    record TransactionResponse(
        Long id,
        String accountId,
        String eventId,
        String type,
        BigDecimal amount,
        String currency
    ) {}

    record BalanceResponse(
        String accountId,
        BigDecimal balance,
        String currency
    ) {}

    record AccountResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        Integer transactionCount
    ) {}
}