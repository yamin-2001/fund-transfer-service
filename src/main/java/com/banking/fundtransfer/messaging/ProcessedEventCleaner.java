package com.banking.fundtransfer.messaging;

import com.banking.fundtransfer.messaging.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class ProcessedEventCleaner {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleaner.class);

    private final ProcessedEventRepository repository;

    public ProcessedEventCleaner(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    // Topics keep messages for 7 days, so ids older than 14 days can no longer be redelivered.
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void clean() {
        int deleted = repository.deleteOlderThan(Instant.now().minus(14, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("Removed {} processed-event records older than 14 days", deleted);
        }
    }
}