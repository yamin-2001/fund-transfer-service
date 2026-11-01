package com.banking.fundtransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.saga")
public record SagaProperties(
        int stepTimeoutSeconds,
        int maxResends,
        int reversalMaxResends,
        int otpIssueTimeoutSeconds,
        int otpResendCooldownSeconds
) {}