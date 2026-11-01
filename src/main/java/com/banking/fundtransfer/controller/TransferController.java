package com.banking.fundtransfer.controller;

import com.banking.fundtransfer.config.TransferProperties;
import com.banking.fundtransfer.dto.CashTransactionRequest;
import com.banking.fundtransfer.dto.ConfirmTransferRequest;
import com.banking.fundtransfer.dto.InitiateTransferRequest;
import com.banking.fundtransfer.dto.TransferResponse;
import com.banking.fundtransfer.dto.TransferStatusResponse;
import com.banking.fundtransfer.entity.TransferType;
import com.banking.fundtransfer.saga.TransferSagaService;
import com.banking.fundtransfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferSagaService sagaService;
    private final TransferProperties properties;

    public TransferController(TransferService transferService,
                              TransferSagaService sagaService,
                              TransferProperties properties) {
        this.transferService = transferService;
        this.sagaService = sagaService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<?> initiate(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String userEmail,
            @Valid @RequestBody InitiateTransferRequest request) {
        if (properties.sagaEnabled()) {
            // 202 Accepted: the checks run in the background. Poll GET /api/transfers/{id}.
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(sagaService.initiate(userId, userEmail, request));
        }
        return ResponseEntity.ok(transferService.initiate(userId, userEmail, request));
    }

    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String userEmail,
            @Valid @RequestBody CashTransactionRequest request) {
        if (properties.sagaEnabled()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(sagaService.initiateCash(userId, userEmail, request, TransferType.DEPOSIT));
        }
        return ResponseEntity.ok(transferService.initiateDeposit(userId, userEmail, request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String userEmail,
            @Valid @RequestBody CashTransactionRequest request) {
        if (properties.sagaEnabled()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(sagaService.initiateCash(userId, userEmail, request, TransferType.WITHDRAWAL));
        }
        return ResponseEntity.ok(transferService.initiateWithdrawal(userId, userEmail, request));
    }

    @PostMapping("/{transferId}/confirm")
    public ResponseEntity<?> confirm(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String userEmail,
            @PathVariable String transferId,
            @Valid @RequestBody ConfirmTransferRequest request) {
        // The transaction itself decides the flow, so a switch of the setting never strands a running one.
        if (sagaService.isSagaTransfer(userId, transferId)) {
            TransferStatusResponse response = sagaService.confirm(userId, transferId, request);
            return ResponseEntity.status(response.finished() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(response);
        }
        return ResponseEntity.ok(transferService.confirm(userId, userEmail, transferId, request));
    }

    @PostMapping("/{transferId}/resend-otp")
    public ResponseEntity<TransferStatusResponse> resendOtp(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String transferId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(sagaService.resendOtp(userId, transferId));
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferStatusResponse> get(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String transferId) {
        return ResponseEntity.ok(sagaService.get(userId, transferId));
    }

    @GetMapping
    public ResponseEntity<List<TransferResponse>> list(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(transferService.list(userId));
    }
}