package com.banking.fundtransfer.service;

import com.banking.fundtransfer.dto.CashTransactionRequest;
import com.banking.fundtransfer.dto.ConfirmTransferRequest;
import com.banking.fundtransfer.dto.ConfirmTransferResponse;
import com.banking.fundtransfer.dto.InitiateTransferRequest;
import com.banking.fundtransfer.dto.InitiateTransferResponse;
import com.banking.fundtransfer.dto.TransferResponse;

import java.util.List;

public interface TransferService {

    InitiateTransferResponse initiate(Long userId, String userEmail, InitiateTransferRequest request);

    InitiateTransferResponse initiateDeposit(Long userId, String userEmail, CashTransactionRequest request);

    InitiateTransferResponse initiateWithdrawal(Long userId, String userEmail, CashTransactionRequest request);

    ConfirmTransferResponse confirm(Long userId, String userEmail, String transferId,
                                    ConfirmTransferRequest request);

    List<TransferResponse> list(Long userId);
}