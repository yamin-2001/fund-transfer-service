package com.banking.fundtransfer.job;

import com.banking.fundtransfer.config.TransferProperties;
import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TransferExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(TransferExpiryJob.class);

    private final TransferRepository transferRepository;
    private final TransferProperties properties;

    public TransferExpiryJob(TransferRepository transferRepository, TransferProperties properties) {
        this.transferRepository = transferRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireStaleTransfers() {
        LocalDateTime now = LocalDateTime.now();
        int expired = transferRepository.expireStale(
                TransferStatus.PENDING_OTP,
                TransferStatus.EXPIRED,
                now.minusMinutes(properties.pendingExpiryMinutes()),
                now);
        if (expired > 0) {
            log.info("Expired {} unconfirmed transfers", expired);
        }
    }
}