package com.banking.fundtransfer.controller;

import com.banking.fundtransfer.dto.ConfirmTransferRequest;
import com.banking.fundtransfer.dto.ConfirmTransferResponse;
import com.banking.fundtransfer.dto.InitiateTransferRequest;
import com.banking.fundtransfer.dto.InitiateTransferResponse;
import com.banking.fundtransfer.dto.TransferResponse;
import com.banking.fundtransfer.service.TransferService;
import jakarta.validation.Valid;
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

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<InitiateTransferResponse> initiate(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String userEmail,
            @Valid @RequestBody InitiateTransferRequest request) {
        return ResponseEntity.ok(transferService.initiate(userId, userEmail, request));
    }

    @PostMapping("/{transferId}/confirm")
    public ResponseEntity<ConfirmTransferResponse> confirm(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String userEmail,
            @PathVariable String transferId,
            @Valid @RequestBody ConfirmTransferRequest request) {
        return ResponseEntity.ok(transferService.confirm(userId, userEmail, transferId, request));
    }

    @GetMapping
    public ResponseEntity<List<TransferResponse>> list(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(transferService.list(userId));
    }
}