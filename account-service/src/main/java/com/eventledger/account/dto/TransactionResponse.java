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
public class TransactionResponse {

    private Long id;

    private String accountId;

    private String eventId;

    private String type;

    private BigDecimal amount;

    private String currency;

    private Instant eventTimestamp;

    private Instant createdAt;

    private String traceId;
}