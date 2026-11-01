package com.banking.fundtransfer.saga;

import com.banking.fundtransfer.entity.Transfer;
import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class SagaTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutSweeper.class);

    private final TransferRepository repository;
    private final TransferSagaEngine engine;

    public SagaTimeoutSweeper(TransferRepository repository, TransferSagaEngine engine) {
        this.repository = repository;
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${app.saga.sweep-interval-ms:5000}")
    public void sweep() {
        for (TransferStatus status : TransferSagaEngine.WATCHED) {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(engine.timeoutSeconds(status));
            List<Transfer> stale = repository
                    .findTop100ByFlowAndStatusAndStateEnteredAtBeforeOrderByStateEnteredAtAsc(
                            TransferSagaEngine.FLOW, status, cutoff);

            for (Transfer transfer : stale) {
                try {
                    engine.handleTimeout(transfer.getTransferId(), status);
                } catch (RuntimeException ex) {
                    log.error("Could not handle the timeout of transfer {}: {}",
                            transfer.getTransferId(), ex.getMessage());
                }
            }
        }
    }
}