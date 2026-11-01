package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record CalculateFeePayload(
        String transferId,
        String fromAccountNumber,
        String toAccountNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        boolean sameOwner
) {}