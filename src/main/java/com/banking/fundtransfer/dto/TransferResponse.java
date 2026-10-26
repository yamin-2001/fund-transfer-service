package com.banking.fundtransfer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
        String transferId,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        BigDecimal fee,
        String status,
        LocalDateTime createdAt
) {}