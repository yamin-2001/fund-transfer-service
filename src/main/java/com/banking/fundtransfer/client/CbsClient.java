package com.banking.fundtransfer.client;

import java.math.BigDecimal;

public interface CbsClient {

    BigDecimal getBalance(String accountNumber);

    CbsPostingResult postTransfer(String referenceId, String fromAccount, String toAccount,
                                  BigDecimal amount, BigDecimal fee);

    CbsPostingResult postDeposit(String referenceId, String accountNumber, BigDecimal amount);

    CbsPostingResult postWithdrawal(String referenceId, String accountNumber, BigDecimal amount);
}