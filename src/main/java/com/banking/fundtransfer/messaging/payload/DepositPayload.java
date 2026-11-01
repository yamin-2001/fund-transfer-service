package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record DepositPayload(
        String transferId,
        String commandId,
        String accountNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String source
) {}