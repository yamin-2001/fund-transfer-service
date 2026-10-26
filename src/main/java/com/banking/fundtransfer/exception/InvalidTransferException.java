package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class InvalidTransferException extends ApiException {
    public InvalidTransferException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}