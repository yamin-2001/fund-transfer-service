package com.banking.fundtransfer.messaging;

import com.banking.fundtransfer.messaging.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query(value = "insert into processed_events (id, processed_at) values (:id, now()) "
            + "on conflict (id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") String id);

    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}