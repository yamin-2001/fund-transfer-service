package com.banking.fundtransfer.entity;

public enum TransferStatus {
    PENDING_OTP,
    PROCESSING,
    UNCONFIRMED,
    SUCCESS,
    FAILED,
    EXPIRED
}