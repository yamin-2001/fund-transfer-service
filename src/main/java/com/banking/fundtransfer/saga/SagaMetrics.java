package com.banking.fundtransfer.saga;

import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.repository.TransferRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SagaMetrics {

    public SagaMetrics(MeterRegistry registry, TransferRepository repository) {
        for (TransferStatus status : TransferStatus.values()) {
            if (!TransferStateMachine.isSagaState(status)) {
                continue;
            }
            Gauge.builder("transfers.in.flight",
                            () -> repository.countByFlowAndStatus(TransferSagaEngine.FLOW, status))
                    .description("Saga transfers per state")
                    .tag("status", status.name())
                    .register(registry);
        }
    }
}