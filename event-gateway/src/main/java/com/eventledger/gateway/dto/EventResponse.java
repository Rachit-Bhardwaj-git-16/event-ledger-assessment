package com.eventledger.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResponse {

    private Long id;

    private String eventId;

    private String accountId;

    private String type;

    private BigDecimal amount;

    private String currency;

    private Instant eventTimestamp;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    private Instant createdAt;

    private String traceId;
}