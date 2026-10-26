package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class ResultUnconfirmedException extends ApiException {
    public ResultUnconfirmedException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}