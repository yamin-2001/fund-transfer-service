package com.banking.fundtransfer.client;

import com.banking.fundtransfer.exception.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class OtpClientImpl implements OtpClient {

    private static final Logger log = LoggerFactory.getLogger(OtpClientImpl.class);

    private final RestClient restClient;

    public OtpClientImpl(@Qualifier("otpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String sendOtp(Long userId, String email, String referenceId) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "email", email,
                "purpose", "TRANSFER",
                "referenceId", referenceId
        );

        ClientPayloads.OtpIssued issued;
        try {
            issued = restClient.post()
                    .uri("/internal/otp/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ClientPayloads.OtpIssued.class);
        } catch (RestClientException | IllegalStateException ex) {
            log.error("OTP send failed: {}", ex.getMessage());
            throw new DownstreamUnavailableException("Could not send the OTP, please try again");
        }

        if (issued == null || issued.otpId() == null) {
            throw new DownstreamUnavailableException("Could not send the OTP, please try again");
        }
        return issued.otpId();
    }

    @Override
    public ClientPayloads.OtpVerification verifyOtp(String otpId, String otp, String referenceId) {
        Map<String, Object> body = Map.of(
                "otpId", otpId,
                "otp", otp,
                "referenceId", referenceId
        );

        ClientPayloads.OtpVerification result;
        try {
            result = restClient.post()
                    .uri("/internal/otp/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ClientPayloads.OtpVerification.class);
        } catch (RestClientException | IllegalStateException ex) {
            log.error("OTP verify failed: {}", ex.getMessage());
            throw new DownstreamUnavailableException("OTP service is unavailable, please try again");
        }

        if (result == null) {
            throw new DownstreamUnavailableException("OTP service is unavailable, please try again");
        }
        return result;
    }
}