package com.banking.fundtransfer.dto;

public record ConfirmTransferResponse(
        String transferId,
        String status,
        String message
) {}