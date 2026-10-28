package com.banking.fundtransfer.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_pending", columnList = "publishedAt, createdAt")
})
public class OutboxEvent {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 255)
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String id, String topic, String messageKey, String payload) {
        this.id = id;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}