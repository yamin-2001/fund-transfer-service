package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawCompletedEvent(String transferId, String accountNumber, BigDecimal amount, BigDecimal fee) {}