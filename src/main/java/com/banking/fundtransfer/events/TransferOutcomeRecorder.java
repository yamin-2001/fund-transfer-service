package com.banking.fundtransfer.events;

import com.banking.fundtransfer.entity.OutboxEvent;
import com.banking.fundtransfer.entity.Transfer;
import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.repository.OutboxEventRepository;
import com.banking.fundtransfer.repository.TransferRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

@Component
public class TransferOutcomeRecorder {

    private static final String COMPLETED_TOPIC = "transfer.completed";
    private static final String FAILED_TOPIC = "transfer.failed";

    private final TransferRepository transferRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper mapper;

    public TransferOutcomeRecorder(TransferRepository transferRepository,
                                   OutboxEventRepository outboxRepository,
                                   ObjectMapper mapper) {
        this.transferRepository = transferRepository;
        this.outboxRepository = outboxRepository;
        this.mapper = mapper;
    }

    @Transactional
    public void recordCompleted(Transfer transfer, String cbsTransactionId, String recipientEmail) {
        transferRepository.finish(transfer.getTransferId(), TransferStatus.SUCCESS, null,
                cbsTransactionId, LocalDateTime.now());
        enqueue(COMPLETED_TOPIC, "TRANSFER_COMPLETED", transfer.getTransferId() + ":completed",
                transfer, recipientEmail, null);
    }

    @Transactional
    public void recordFailed(Transfer transfer, String reason, String recipientEmail) {
        transferRepository.finish(transfer.getTransferId(), TransferStatus.FAILED, reason,
                null, LocalDateTime.now());
        enqueue(FAILED_TOPIC, "TRANSFER_FAILED", transfer.getTransferId() + ":failed",
                transfer, recipientEmail, reason);
    }

    private void enqueue(String topic, String type, String eventId, Transfer transfer,
                         String recipientEmail, String reason) {
        // The same transfer can never produce the same event twice.
        if (outboxRepository.existsById(eventId)) {
            return;
        }

        TransferEvent event = new TransferEvent(
                eventId,
                type,
                1,
                Instant.now().toString(),
                transfer.getTransferId(),
                transfer.getType().name(),
                recipientEmail,
                transfer.getFromAccountNumber(),
                transfer.getToAccountNumber(),
                transfer.getAmount().toPlainString(),
                transfer.getFee().toPlainString(),
                reason);

        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize the transfer event", ex);
        }

        outboxRepository.save(new OutboxEvent(eventId, topic, transfer.getTransferId(), payload));
    }
}