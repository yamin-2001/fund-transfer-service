package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class ResendTooSoonException extends ApiException {
    public ResendTooSoonException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}