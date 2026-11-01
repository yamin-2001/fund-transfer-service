package com.banking.fundtransfer.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OtpDeliveryFailedEvent(String transferId, String reason) {}