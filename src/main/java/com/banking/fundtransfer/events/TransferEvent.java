package com.banking.fundtransfer.events;

/** Version 1 of the events on topics transfer.completed and transfer.failed. */
public record TransferEvent(
        String eventId,
        String eventType,
        int version,
        String occurredAt,
        String transferId,
        String transferType,
        String recipientEmail,
        String fromAccount,
        String toAccount,
        String amount,
        String fee,
        String reason
) {}