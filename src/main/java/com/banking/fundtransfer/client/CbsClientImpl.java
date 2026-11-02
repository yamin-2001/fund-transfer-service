package com.banking.fundtransfer.client;

import com.banking.fundtransfer.exception.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class CbsClientImpl implements CbsClient {

    private static final Logger log = LoggerFactory.getLogger(CbsClientImpl.class);

    private final RestClient restClient;

    public CbsClientImpl(@Qualifier("cbsRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public BigDecimal getBalance(String accountNumber) {
        ClientPayloads.LedgerBalance result;
        try {
            result = restClient.get()
                    .uri("/internal/cbs/accounts/{number}/balance", accountNumber)
                    .retrieve()
                    .body(ClientPayloads.LedgerBalance.class);
        } catch (RuntimeException ex) {
            log.error("CBS balance lookup failed: {}", ex.getMessage());
            throw new DownstreamUnavailableException("Banking core is unavailable, please try again");
        }

        if (result == null || result.balance() == null) {
            throw new DownstreamUnavailableException("Banking core returned an invalid response");
        }
        return result.balance();
    }

    @Override
    public CbsPostingResult postTransfer(String referenceId, String fromAccount, String toAccount,
                                         BigDecimal amount, BigDecimal fee) {
        return post("/internal/cbs/transfers", referenceId, Map.of(
                "referenceId", referenceId,
                "fromAccountNumber", fromAccount,
                "toAccountNumber", toAccount,
                "amount", amount,
                "fee", fee
        ));
    }

    @Override
    public CbsPostingResult postDeposit(String referenceId, String accountNumber, BigDecimal amount) {
        return post("/internal/cbs/deposits", referenceId, Map.of(
                "referenceId", referenceId,
                "accountNumber", accountNumber,
                "amount", amount
        ));
    }

    @Override
    public CbsPostingResult postWithdrawal(String referenceId, String accountNumber, BigDecimal amount) {
        return post("/internal/cbs/withdrawals", referenceId, Map.of(
                "referenceId", referenceId,
                "accountNumber", accountNumber,
                "amount", amount
        ));
    }

    private CbsPostingResult post(String uri, String referenceId, Map<String, Object> body) {
        try {
            ClientPayloads.LedgerPosting posting = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ClientPayloads.LedgerPosting.class);

            if (posting == null || posting.transactionId() == null || !"POSTED".equals(posting.status())) {
                return CbsPostingResult.unknown();
            }
            return CbsPostingResult.posted(posting.transactionId());
        } catch (HttpClientErrorException ex) {
            int status = ex.getStatusCode().value();
            // 408 / 409 / 429 do not tell us the posting failed, so treat them as unknown and retry later.
            if (status == 408 || status == 409 || status == 429) {
                return CbsPostingResult.unknown();
            }
            return CbsPostingResult.rejected(extractMessage(ex));
        } catch (RuntimeException ex) {
            // Timeout, connection problem, or CBS error: the money may or may not have moved.
            log.error("CBS posting outcome unknown for {}: {}", referenceId, ex.getMessage());
            return CbsPostingResult.unknown();
        }
    }

    private String extractMessage(HttpClientErrorException ex) {
        try {
            ClientPayloads.ErrorBody error = ex.getResponseBodyAs(ClientPayloads.ErrorBody.class);
            if (error != null && error.message() != null && !error.message().isBlank()) {
                return error.message();
            }
        } catch (Exception ignored) {
            // fall through to the generic message
        }
        return "Transfer rejected by the banking core";
    }
}