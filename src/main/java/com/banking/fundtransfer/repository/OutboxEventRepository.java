package com.banking.fundtransfer.repository;

import com.banking.fundtransfer.messaging.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    long countByPublishedAtIsNull();

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent e set e.publishedAt = :now where e.id = :id and e.publishedAt is null")
    int markPublished(@Param("id") String id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1, e.lastError = :error where e.id = :id")
    int recordFailure(@Param("id") String id, @Param("error") String error);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);
}