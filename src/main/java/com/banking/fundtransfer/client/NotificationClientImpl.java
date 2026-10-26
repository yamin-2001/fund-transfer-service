package com.banking.fundtransfer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class NotificationClientImpl implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClientImpl.class);

    private final RestClient restClient;

    public NotificationClientImpl(@Qualifier("notificationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void sendTransferSuccess(String to, String reference, String fromAccount, String toAccount,
                                    BigDecimal amount, BigDecimal fee) {
        send(to, "TRANSFER_SUCCESS", Map.of(
                "amount", amount.toPlainString(),
                "fromAccount", fromAccount,
                "toAccount", toAccount,
                "fee", fee.toPlainString(),
                "reference", reference
        ));
    }

    @Override
    public void sendTransferFailed(String to, String fromAccount, String toAccount,
                                   BigDecimal amount, String reason) {
        send(to, "TRANSFER_FAILED", Map.of(
                "amount", amount.toPlainString(),
                "fromAccount", fromAccount,
                "toAccount", toAccount,
                "reason", reason == null ? "Unknown" : reason
        ));
    }

    private void send(String to, String type, Map<String, String> params) {
        Map<String, Object> body = Map.of("to", to, "type", type, "params", params);
        try {
            restClient.post()
                    .uri("/internal/notifications/templated")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.error("Could not send {} notification: {}", type, ex.getMessage());
        }
    }
}