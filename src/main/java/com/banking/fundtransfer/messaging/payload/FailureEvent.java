package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FailureEvent(String transferId, String reasonCode, String reason) {}