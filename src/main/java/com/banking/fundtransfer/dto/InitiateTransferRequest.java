package com.banking.fundtransfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record InitiateTransferRequest(
        @NotBlank(message = "fromAccountNumber is required")
        @Size(max = 50, message = "fromAccountNumber must be at most 50 characters")
        String fromAccountNumber,

        @NotBlank(message = "toAccountNumber is required")
        @Size(max = 50, message = "toAccountNumber must be at most 50 characters")
        String toAccountNumber,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 15, fraction = 2, message = "amount allows at most 2 decimal places")
        BigDecimal amount
) {}