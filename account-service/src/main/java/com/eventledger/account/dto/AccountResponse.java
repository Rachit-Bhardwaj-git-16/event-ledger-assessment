package com.eventledger.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountResponse {
    private String accountId;
    private BigDecimal balance;
    private String currency;
    private Integer transactionCount;
    private Instant createdAt;
}
