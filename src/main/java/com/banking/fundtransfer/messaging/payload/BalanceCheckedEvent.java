package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The account can cover the debit. The available balance is carried along for the screen. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceCheckedEvent(
        String transferId,
        String accountNumber,
        BigDecimal availableBalance,
        BigDecimal requiredAmount
) {}
