package com.banking.fundtransfer.client;

import com.banking.fundtransfer.exception.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AccountClientImpl implements AccountClient {

    private static final Logger log = LoggerFactory.getLogger(AccountClientImpl.class);

    private final RestClient restClient;

    public AccountClientImpl(@Qualifier("accountRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Map<String, AccountInfo> findByNumbers(Collection<String> accountNumbers) {
        try {
            List<AccountInfo> accounts = restClient.get()
                    .uri(uri -> uri.path("/internal/accounts")
                            .queryParam("accountNumbers", String.join(",", accountNumbers))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<AccountInfo>>() {});

            if (accounts == null) {
                return Map.of();
            }
            return accounts.stream()
                    .collect(Collectors.toMap(AccountInfo::accountNumber, Function.identity()));
        } catch (RestClientException | IllegalStateException ex) {
            log.error("Account service call failed: {}", ex.getMessage());
            throw new DownstreamUnavailableException("Account service is unavailable, please try again");
        }
    }
}