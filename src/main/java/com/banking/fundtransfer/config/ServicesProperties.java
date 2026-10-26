package com.banking.fundtransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.services")
public record ServicesProperties(
        String account,
        String chargeFee,
        String otp,
        String cbs,
        String notification
) {}