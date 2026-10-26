package com.banking.fundtransfer.client;

import java.math.BigDecimal;

public interface NotificationClient {

    void sendTransferSuccess(String to, String reference, String fromAccount, String toAccount,
                             BigDecimal amount, BigDecimal fee);

    void sendTransferFailed(String to, String fromAccount, String toAccount,
                            BigDecimal amount, String reason);
}