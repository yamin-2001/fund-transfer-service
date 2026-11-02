package com.banking.fundtransfer.client;

import com.banking.fundtransfer.exception.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class ChargeFeeClientImpl implements ChargeFeeClient {

    private static final Logger log = LoggerFactory.getLogger(ChargeFeeClientImpl.class);

    private final RestClient restClient;

    public ChargeFeeClientImpl(@Qualifier("feeRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public BigDecimal calculateFee(String fromAccount, String toAccount, BigDecimal amount, boolean sameOwner) {
        Map<String, Object> body = Map.of(
                "fromAccountNumber", fromAccount,
                "toAccountNumber", toAccount,
                "amount", amount,
                "sameOwner", sameOwner
        );

        ClientPayloads.FeeCalculation result;
        try {
            result = restClient.post()
                    .uri("/internal/fees/calculate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ClientPayloads.FeeCalculation.class);
        } catch (RuntimeException ex) {
            log.error("Charge fee service call failed: {}", ex.getMessage());
            throw new DownstreamUnavailableException("Fee service is unavailable, please try again");
        }

        if (result == null || result.fee() == null || result.fee().signum() < 0) {
            throw new DownstreamUnavailableException("Fee service returned an invalid response");
        }
        return result.fee().setScale(2);
    }
}