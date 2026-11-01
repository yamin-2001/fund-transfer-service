package com.banking.fundtransfer.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(length = 160)
    private String id;

    @Column(nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}

    public String getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
}