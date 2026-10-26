package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class NotAccountOwnerException extends ApiException {
    public NotAccountOwnerException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}