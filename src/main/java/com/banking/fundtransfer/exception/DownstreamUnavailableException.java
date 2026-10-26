package com.banking.fundtransfer.exception;

import org.springframework.http.HttpStatus;

public class DownstreamUnavailableException extends ApiException {
    public DownstreamUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}