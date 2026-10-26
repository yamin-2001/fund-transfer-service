package com.banking.fundtransfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

public final class ClientPayloads {

    private ClientPayloads() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FeeCalculation(BigDecimal fee) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OtpIssued(String otpId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OtpVerification(boolean verified, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LedgerBalance(BigDecimal balance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LedgerPosting(String transactionId, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorBody(String message) {}
}