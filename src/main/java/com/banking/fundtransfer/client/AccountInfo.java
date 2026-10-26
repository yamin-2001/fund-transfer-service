package com.banking.fundtransfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInfo(
        String accountNumber,
        Long userId,
        String accountType,
        String currency,
        String status
) {}