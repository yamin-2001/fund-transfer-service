package com.banking.fundtransfer.entity;

public enum TransferStatus {
    // the original synchronous flow
    PENDING_OTP,
    PROCESSING,
    UNCONFIRMED,
    SUCCESS,

    // both flows
    FAILED,
    EXPIRED,

    // the saga flow
    VALIDATING,
    FEE_PENDING,
    OTP_PENDING,
    AWAITING_OTP,
    WITHDRAWING,
    DEPOSITING,
    COMPLETED,
    REVERSING,
    REVERSED,
    RECONCILIATION_REQUIRED
}