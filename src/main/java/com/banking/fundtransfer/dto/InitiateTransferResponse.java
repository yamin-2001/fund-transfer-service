package com.banking.fundtransfer.dto;

import java.math.BigDecimal;

public record InitiateTransferResponse(
        String transferId,
        BigDecimal fee,
        BigDecimal totalDebit,
        String status,
        String message
) {}