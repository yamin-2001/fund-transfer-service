package com.banking.fundtransfer.messaging.payload;

public record ReconciliationRequiredPayload(String transferId, String state, String details) {}