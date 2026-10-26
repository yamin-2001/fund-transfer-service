package com.banking.fundtransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.transfer")
public record TransferProperties(
        int pendingExpiryMinutes,
        int maxPendingPerUser,
        String notifyEmail
) {}