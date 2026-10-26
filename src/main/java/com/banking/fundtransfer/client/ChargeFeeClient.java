package com.banking.fundtransfer.client;

import java.math.BigDecimal;

public interface ChargeFeeClient {

    BigDecimal calculateFee(String fromAccount, String toAccount, BigDecimal amount, boolean sameOwner);
}