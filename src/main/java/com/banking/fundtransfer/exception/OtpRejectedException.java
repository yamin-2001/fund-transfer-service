package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class OtpRejectedException extends ApiException {
    public OtpRejectedException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}