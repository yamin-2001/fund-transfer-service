package com.banking.fundtransfer.client;

public interface OtpClient {

    /** Sends an OTP email and returns the otpId. */
    String sendOtp(Long userId, String email, String referenceId);

    ClientPayloads.OtpVerification verifyOtp(String otpId, String otp, String referenceId);
}