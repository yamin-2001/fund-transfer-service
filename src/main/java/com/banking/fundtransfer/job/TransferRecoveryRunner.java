package com.banking.fundtransfer.job;

import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TransferRecoveryRunner implements ApplicationRunner {

//    private static final Logger log = LoggerFactory.getLogger(TransferRecoveryRunner.class);
//
//    private final TransferRepository transferRepository;
//
    public TransferRecoveryRunner(TransferRepository transferRepository) {
//        this.transferRepository = transferRepository;
    }
//
    @Override
    public void run(ApplicationArguments args) {
//        LocalDateTime now = LocalDateTime.now();
//
//        // OTP was never confirmed as verified: nothing was posted, so the user can try the OTP again.
//        int backToPending = transferRepository.recover(
//                TransferStatus.PROCESSING, TransferStatus.PENDING_OTP, false, now);
//
//        // OTP was verified: CBS may or may not have posted. Retrying is safe because CBS is idempotent.
//        int unconfirmed = transferRepository.recover(
//                TransferStatus.PROCESSING, TransferStatus.UNCONFIRMED, true, now);
//
//        if (backToPending > 0 || unconfirmed > 0) {
//            log.warn("Recovered interrupted transfers: {} back to PENDING_OTP, {} to UNCONFIRMED",
//                    backToPending, unconfirmed);
//        }
    }
}