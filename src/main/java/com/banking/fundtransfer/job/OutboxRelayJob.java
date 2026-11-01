package com.banking.fundtransfer.job;
//
//import com.banking.fundtransfer.messaging.OutboxEvent;
//import com.banking.fundtransfer.repository.OutboxEventRepository;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.concurrent.TimeUnit;
//
///**
// * Publishes outbox rows to Kafka. Delivery is at least once: a crash after the publish but before the
// * row is marked can publish the same event twice, which is why the consumer ignores repeated event ids.
// * Assumes one running instance of this service.
// */
//@Component
public class OutboxRelayJob {
//
//    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);
//
//    private final OutboxEventRepository outbox;
//    private final KafkaTemplate<String, String> kafka;
//    private final int retentionDays;
//
//    public OutboxRelayJob(OutboxEventRepository outbox,
//                          KafkaTemplate<String, String> kafka,
//                          @Value("${app.outbox.retention-days:7}") int retentionDays) {
//        this.outbox = outbox;
//        this.kafka = kafka;
//        this.retentionDays = retentionDays;
//    }
//
//    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
//    public void publishPending() {
//        List<OutboxEvent> pending = outbox.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
//
//        for (OutboxEvent event : pending) {
//            try {
//                kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload())
//                        .get(5, TimeUnit.SECONDS);
//                outbox.markPublished(event.getId(), LocalDateTime.now());
//                log.info("Outbox: published event {} to {}", event.getId(), event.getTopic());
//            } catch (InterruptedException ex) {
//                Thread.currentThread().interrupt();
//                return;
//            } catch (Exception ex) {
//                outbox.recordFailure(event.getId(), truncate(ex.getMessage()));
//                // Warn on the first failure and then every 30th, so a long outage does not flood the log.
//                if (event.getAttempts() % 30 == 0) {
//                    log.warn("Outbox: could not publish event {} (attempt {}), will retry: {}",
//                            event.getId(), event.getAttempts() + 1, ex.getMessage());
//                }
//                // Stop here to keep the order. The next run tries again.
//                return;
//            }
//        }
//    }
//
//    @Scheduled(cron = "0 30 3 * * *")
//    public void deleteOldPublishedEvents() {
//        int deleted = outbox.deletePublishedBefore(LocalDateTime.now().minusDays(retentionDays));
//        if (deleted > 0) {
//            log.info("Outbox: deleted {} old published events", deleted);
//        }
//    }
//
//    private String truncate(String text) {
//        if (text == null) {
//            return "unknown error";
//        }
//        return text.length() > 255 ? text.substring(0, 255) : text;
//    }
}