package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record TransferOutcomePayload(
        String transferId,
        String type,
        Long userId,
        String email,
        String fromAccountNumber,
        String toAccountNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fee,
        String reason
) {}