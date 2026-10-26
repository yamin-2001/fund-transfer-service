package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class TooManyPendingTransfersException extends ApiException {
    public TooManyPendingTransfersException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}