package com.banking.fundtransfer.messaging.consumer;

import com.banking.fundtransfer.messaging.payload.CashAccountValidatedEvent;
import com.banking.fundtransfer.messaging.EventEnvelope;
import com.banking.fundtransfer.messaging.EventParser;
import com.banking.fundtransfer.messaging.TopicNames;
import com.banking.fundtransfer.messaging.payload.DepositCompletedEvent;
import com.banking.fundtransfer.messaging.payload.FailureEvent;
import com.banking.fundtransfer.messaging.payload.FeeCalculatedEvent;
import com.banking.fundtransfer.messaging.payload.OtpDeliveryFailedEvent;
import com.banking.fundtransfer.messaging.payload.OtpIssuedEvent;
import com.banking.fundtransfer.messaging.payload.ReversalCompletedEvent;
import com.banking.fundtransfer.messaging.payload.TransferValidatedEvent;
import com.banking.fundtransfer.messaging.payload.WithdrawCompletedEvent;
import com.banking.fundtransfer.saga.TransferSagaEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SagaEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SagaEventsConsumer.class);

    private final EventParser parser;
    private final TransferSagaEngine engine;
    private final MeterRegistry meters;

    public SagaEventsConsumer(EventParser parser, TransferSagaEngine engine, MeterRegistry meters) {
        this.parser = parser;
        this.engine = engine;
        this.meters = meters;
    }

    @KafkaListener(topics = {
            TopicNames.ACCOUNT_EVENTS,
            TopicNames.FEE_EVENTS,
            TopicNames.OTP_EVENTS,
            TopicNames.NOTIFICATION_EVENTS,
            TopicNames.CBS_EVENTS})
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        switch (envelope.eventType()) {
            case "TransferValidated" -> engine.onTransferValidated(envelope,
                    parser.payload(envelope, TransferValidatedEvent.class));
            case "TransferRejected" -> engine.onTransferRejected(envelope,
                    parser.payload(envelope, FailureEvent.class));
            case "CashAccountValidated" -> engine.onCashAccountValidated(envelope,
                    parser.payload(envelope, CashAccountValidatedEvent.class));
            case "CashAccountRejected" -> engine.onCashAccountRejected(envelope,
                    parser.payload(envelope, FailureEvent.class));
            case "FeeCalculated" -> engine.onFeeCalculated(envelope,
                    parser.payload(envelope, FeeCalculatedEvent.class));
            case "OtpIssued" -> engine.onOtpIssued(envelope,
                    parser.payload(envelope, OtpIssuedEvent.class));
            case "OtpDeliveryFailed" -> engine.onOtpDeliveryFailed(envelope,
                    parser.payload(envelope, OtpDeliveryFailedEvent.class));
            case "WithdrawCompleted" -> engine.onWithdrawCompleted(envelope,
                    parser.payload(envelope, WithdrawCompletedEvent.class));
            case "WithdrawFailed" -> engine.onWithdrawFailed(envelope,
                    parser.payload(envelope, FailureEvent.class));
            case "DepositCompleted" -> engine.onDepositCompleted(envelope,
                    parser.payload(envelope, DepositCompletedEvent.class));
            case "DepositFailed" -> engine.onDepositFailed(envelope,
                    parser.payload(envelope, FailureEvent.class));
            case "ReversalCompleted" -> engine.onReversalCompleted(envelope,
                    parser.payload(envelope, ReversalCompletedEvent.class));
            case "ReversalFailed" -> engine.onReversalFailed(envelope,
                    parser.payload(envelope, FailureEvent.class));
            case "OtpIssueRejected" -> {
                // A resend came too early. Nothing changes: the old code stays valid.
                meters.counter("saga.events", "type", "OtpIssueRejected", "result", "noted").increment();
            }
            default -> log.debug("Ignoring event type {} on {}", envelope.eventType(), record.topic());
        }
    }
}