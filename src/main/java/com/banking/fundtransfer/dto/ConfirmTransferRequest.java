package com.banking.fundtransfer.dto;

import jakarta.validation.constraints.Pattern;

public record ConfirmTransferRequest(
        @Pattern(regexp = "\\d{6}", message = "otp must be exactly 6 digits")
        String otp
) {}