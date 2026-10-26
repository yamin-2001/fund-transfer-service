package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class TransferInProgressException extends ApiException {
    public TransferInProgressException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}