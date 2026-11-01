package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record ValidateTransferPayload(
        String transferId,
        Long userId,
        String fromAccountNumber,
        String toAccountNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount
) {}