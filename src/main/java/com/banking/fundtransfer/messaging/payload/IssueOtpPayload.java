package com.banking.fundtransfer.messaging.payload;

public record IssueOtpPayload(String transferId, Long userId, String email, String purpose) {}