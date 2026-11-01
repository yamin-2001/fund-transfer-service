package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeeCalculatedEvent(String transferId, BigDecimal fee, String feeType) {}