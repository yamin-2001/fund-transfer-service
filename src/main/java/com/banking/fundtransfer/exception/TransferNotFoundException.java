package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class TransferNotFoundException extends ApiException {
    public TransferNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}