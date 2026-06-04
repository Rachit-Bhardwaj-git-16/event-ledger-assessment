package com.eventledger.account.service;

import com.eventledger.account.dto.*;
import com.eventledger.account.entity.Account;
import com.eventledger.account.entity.Transaction;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private AccountService accountService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        accountService = new AccountService(accountRepository, transactionRepository, meterRegistry);
    }

    @Test
    void testRecordTransaction_NewAccount() {
        // Arrange
        TransactionRequest request = TransactionRequest.builder()
            .eventId("evt-001")
            .type("CREDIT")
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .eventTimestamp(Instant.now())
            .build();

        Account newAccount = Account.builder()
            .id(1L)
            .accountId("acct-123")
            .balance(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        Transaction savedTransaction = Transaction.builder()
            .id(1L)
            .accountId("acct-123")
            .eventId("evt-001")
            .type(Transaction.TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .eventTimestamp(Instant.now())
            .build();

        when(transactionRepository.findByEventId("evt-001")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountId("acct-123")).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenReturn(newAccount);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);
        when(transactionRepository.findByAccountIdOrderedByTimestamp("acct-123"))
            .thenReturn(List.of(savedTransaction));

        // Act
        TransactionResponse response = accountService.recordTransaction("acct-123", request, "trace-123");

        // Assert
        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());
        assertEquals("CREDIT", response.getType());
        verify(accountRepository, times(2)).save(any(Account.class));
    }

    @Test
    void testRecordTransaction_Idempotency() {
        // Arrange
        TransactionRequest request = TransactionRequest.builder()
            .eventId("evt-001")
            .type("CREDIT")
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .eventTimestamp(Instant.now())
            .build();

        Transaction existingTransaction = Transaction.builder()
            .id(1L)
            .accountId("acct-123")
            .eventId("evt-001")
            .type(Transaction.TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        when(transactionRepository.findByEventId("evt-001")).thenReturn(Optional.of(existingTransaction));

        // Act
        TransactionResponse response = accountService.recordTransaction("acct-123", request, "trace-123");

        // Assert
        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void testBalanceCalculation_CreditAndDebit() {
        // Arrange
        Account account = Account.builder()
            .id(1L)
            .accountId("acct-123")
            .balance(BigDecimal.ZERO)
            .currency("USD")
            .build();

        Transaction credit = Transaction.builder()
            .id(1L)
            .accountId("acct-123")
            .eventId("evt-001")
            .type(Transaction.TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .eventTimestamp(Instant.now().minusSeconds(60))
            .build();

        Transaction debit = Transaction.builder()
            .id(2L)
            .accountId("acct-123")
            .eventId("evt-002")
            .type(Transaction.TransactionType.DEBIT)
            .amount(new BigDecimal("30.00"))
            .currency("USD")
            .eventTimestamp(Instant.now())
            .build();

        when(accountRepository.findByAccountId("acct-123")).thenReturn(Optional.of(account));
        when(transactionRepository.findByAccountIdOrderedByTimestamp("acct-123"))
            .thenReturn(List.of(credit, debit));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        // Act
        BalanceResponse response = accountService.getBalance("acct-123");

        // Assert
        assertNotNull(response);
        assertEquals(new BigDecimal("70.00"), response.getBalance());
    }

    @Test
    void testGetBalance_NotFound() {
        // Arrange
        when(accountRepository.findByAccountId("acct-unknown")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(RuntimeException.class, () -> accountService.getBalance("acct-unknown"));
    }

    @Test
    void testGetAccount() {
        // Arrange
        Account account = Account.builder()
            .id(1L)
            .accountId("acct-123")
            .balance(new BigDecimal("100.00"))
            .currency("USD")
            .createdAt(Instant.now())
            .build();

        when(accountRepository.findByAccountId("acct-123")).thenReturn(Optional.of(account));
        when(transactionRepository.findByAccountIdOrderedByTimestamp("acct-123"))
            .thenReturn(List.of());

        // Act
        AccountResponse response = accountService.getAccount("acct-123");

        // Assert
        assertNotNull(response);
        assertEquals("acct-123", response.getAccountId());
        assertEquals(0, response.getTransactionCount());
    }
}