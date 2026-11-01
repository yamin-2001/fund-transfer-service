package com.banking.fundtransfer.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProcessedEventGuard {

    private final ProcessedEventRepository repository;

    public ProcessedEventGuard(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /** Returns false when this event was already handled. Joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String handler, String eventId) {
        return repository.insertIfAbsent(handler + ":" + eventId) == 1;
    }
}