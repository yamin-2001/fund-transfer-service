package com.banking.fundtransfer.client;

import java.util.Collection;
import java.util.Map;

public interface AccountClient {

    /** Returns the accounts that exist, keyed by account number. Unknown numbers are simply absent. */
    Map<String, AccountInfo> findByNumbers(Collection<String> accountNumbers);
}