package com.eventledger.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
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
public class EventRequest {

    @NotBlank(message = "Event ID cannot be blank")
    @Size(min = 1, max = 50, message = "Event ID must be between 1 and 50 characters")
    private String eventId;

    @NotBlank(message = "Account ID cannot be blank")
    @Size(min = 1, max = 50, message = "Account ID must be between 1 and 50 characters")
    private String accountId;

    @NotBlank(message = "Event type cannot be blank")
    @Pattern(regexp = "CREDIT|DEBIT", message = "Event type must be CREDIT or DEBIT")
    private String type;

    @NotNull(message = "Amount cannot be null")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 16, fraction = 2, message = "Amount format is invalid")
    private BigDecimal amount;

    @NotBlank(message = "Currency cannot be blank")
    @Size(min = 3, max = 3, message = "Currency must be exactly 3 characters")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be uppercase ISO 4217 code")
    private String currency;

    @NotNull(message = "Event timestamp cannot be null")
    private Instant eventTimestamp;

    @JsonProperty("metadata")
    private Map<String, String> metadata;
}
