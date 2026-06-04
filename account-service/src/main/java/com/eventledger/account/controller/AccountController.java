package com.eventledger.account.controller;

import com.eventledger.account.dto.*;
import com.eventledger.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
@Slf4j
@Tag(name = "Accounts", description = "Account management endpoints")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/transactions")
    @Operation(summary = "Record a new transaction")
    public ResponseEntity<TransactionResponse> recordTransaction(
            @Parameter(description = "Account ID")
            @PathVariable("accountId") String accountId,
            @Valid @RequestBody TransactionRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (traceId != null) {
            MDC.put("traceId", traceId);
        }

        log.info("POST /accounts/{}/transactions - Recording transaction", accountId);
        TransactionResponse response = accountService.recordTransaction(accountId, request, traceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get account balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @Parameter(description = "Account ID")
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (traceId != null) {
            MDC.put("traceId", traceId);
        }

        log.info("GET /accounts/{}/balance - Fetching balance", accountId);
        BalanceResponse response = accountService.getBalance(accountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details")
    public ResponseEntity<AccountResponse> getAccount(
            @Parameter(description = "Account ID")
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (traceId != null) {
            MDC.put("traceId", traceId);
        }

        log.info("GET /accounts/{} - Fetching account", accountId);
        AccountResponse response = accountService.getAccount(accountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}