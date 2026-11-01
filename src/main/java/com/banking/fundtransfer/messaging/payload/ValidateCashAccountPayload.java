package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record ValidateCashAccountPayload(
        String transferId,
        Long userId,
        String accountNumber,
        String operation,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount
) {}