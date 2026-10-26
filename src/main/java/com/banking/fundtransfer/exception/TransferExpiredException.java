package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class TransferExpiredException extends ApiException {
    public TransferExpiredException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}