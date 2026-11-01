package com.banking.fundtransfer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferStatusResponse(
        String transferId,
        String type,
        String status,
        boolean finished,
        String message,
        String reasonCode,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal totalDebit,
        LocalDateTime otpExpiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}