package com.eventledger.account.service;

import com.eventledger.account.dto.*;
import com.eventledger.account.entity.Account;
import com.eventledger.account.entity.Transaction;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final Counter accountTransactionsCounter;

    public AccountService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.accountTransactionsCounter = Counter.builder("account_transactions_total")
            .description("Total number of transactions")
            .register(meterRegistry);
    }

    @Transactional
    public TransactionResponse recordTransaction(String accountId, TransactionRequest request, String traceId) {
        log.info("Recording transaction for account: {} with event: {}", accountId, request.getEventId());

        // Check idempotency
        var existingTransaction = transactionRepository.findByEventId(request.getEventId());
        if (existingTransaction.isPresent()) {
            log.warn("Transaction with event ID: {} already exists", request.getEventId());
            return mapToTransactionResponse(existingTransaction.get());
        }

        // Get or create account
        Account account = accountRepository.findByAccountId(accountId)
            .orElseGet(() -> {
                log.info("Creating new account: {}", accountId);
                return accountRepository.save(Account.builder()
                    .accountId(accountId)
                    .balance(BigDecimal.ZERO)
                    .currency(request.getCurrency())
                    .build());
            });

        // Record transaction
        Transaction transaction = Transaction.builder()
            .accountId(accountId)
            .eventId(request.getEventId())
            .type(Transaction.TransactionType.valueOf(request.getType().toUpperCase()))
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .eventTimestamp(request.getEventTimestamp())
            .traceId(traceId)
            .build();

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction recorded: {}", savedTransaction.getId());

        // Recalculate balance
        recalculateBalance(account);

        accountTransactionsCounter.increment();
        return mapToTransactionResponse(savedTransaction);
    }

    public BalanceResponse getBalance(String accountId) {
        log.info("Fetching balance for account: {}", accountId);
        
        Account account = accountRepository.findByAccountId(accountId)
            .orElseThrow(() -> {
                log.warn("Account not found: {}", accountId);
                return new RuntimeException("Account not found");
            });

        return BalanceResponse.builder()
            .accountId(accountId)
            .balance(account.getBalance())
            .currency(account.getCurrency())
            .build();
    }

    public AccountResponse getAccount(String accountId) {
        log.info("Fetching account: {}", accountId);
        
        Account account = accountRepository.findByAccountId(accountId)
            .orElseThrow(() -> {
                log.warn("Account not found: {}", accountId);
                return new RuntimeException("Account not found");
            });

        List<Transaction> transactions = transactionRepository.findByAccountIdOrderedByTimestamp(accountId);

        return AccountResponse.builder()
            .accountId(accountId)
            .balance(account.getBalance())
            .currency(account.getCurrency())
            .transactionCount(transactions.size())
            .createdAt(account.getCreatedAt())
            .build();
    }

    @Transactional
    void recalculateBalance(Account account) {
        List<Transaction> transactions = transactionRepository.findByAccountIdOrderedByTimestamp(account.getAccountId());

        BigDecimal balance = BigDecimal.ZERO;
        for (Transaction transaction : transactions) {
            if (transaction.getType() == Transaction.TransactionType.CREDIT) {
                balance = balance.add(transaction.getAmount());
            } else {
                balance = balance.subtract(transaction.getAmount());
            }
        }

        account.setBalance(balance);
        accountRepository.save(account);
        log.debug("Balance recalculated for account {}: {}", account.getAccountId(), balance);
    }

    private TransactionResponse mapToTransactionResponse(Transaction transaction) {
        return TransactionResponse.builder()
            .id(transaction.getId())
            .accountId(transaction.getAccountId())
            .eventId(transaction.getEventId())
            .type(transaction.getType().name())
            .amount(transaction.getAmount())
            .currency(transaction.getCurrency())
            .eventTimestamp(transaction.getEventTimestamp())
            .createdAt(transaction.getCreatedAt())
            .traceId(transaction.getTraceId())
            .build();
    }
}