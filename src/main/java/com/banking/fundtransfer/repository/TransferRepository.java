package com.banking.fundtransfer.repository;

import com.banking.fundtransfer.entity.Transfer;
import com.banking.fundtransfer.entity.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {

    Optional<Transfer> findByTransferIdAndUserId(String transferId, Long userId);

    List<Transfer> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndStatusAndCreatedAtAfter(Long userId, TransferStatus status, LocalDateTime after);

    /** Atomic status change. Returns 0 when the transfer was not in the expected status. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.status = :to, t.updatedAt = :now "
            + "where t.transferId = :id and t.userId = :userId and t.status = :from")
    int transition(@Param("id") String id,
                   @Param("userId") Long userId,
                   @Param("from") TransferStatus from,
                   @Param("to") TransferStatus to,
                   @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.otpId = :otpId, t.updatedAt = :now where t.transferId = :id")
    int setOtpId(@Param("id") String id, @Param("otpId") String otpId, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.otpVerified = true, t.updatedAt = :now where t.transferId = :id")
    int markOtpVerified(@Param("id") String id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.status = :status, t.failureReason = :reason, "
            + "t.cbsTransactionId = :cbsId, t.updatedAt = :now where t.transferId = :id")
    int finish(@Param("id") String id,
               @Param("status") TransferStatus status,
               @Param("reason") String reason,
               @Param("cbsId") String cbsId,
               @Param("now") LocalDateTime now);

    /** Startup recovery for transfers that were mid-flight when the service stopped. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.status = :to, t.updatedAt = :now "
            + "where t.status = :from and t.otpVerified = :verified")
    int recover(@Param("from") TransferStatus from,
                @Param("to") TransferStatus to,
                @Param("verified") boolean verified,
                @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.status = :to, t.updatedAt = :now "
            + "where t.status = :from and t.createdAt < :cutoff")
    int expireStale(@Param("from") TransferStatus from,
                    @Param("to") TransferStatus to,
                    @Param("cutoff") LocalDateTime cutoff,
                    @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- saga

    /** Locks the row until the end of the transaction, so events of one transfer are handled one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.transferId = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") String id);

    long countByUserIdAndFlowAndStatusInAndCreatedAtAfter(Long userId, String flow,
                                                          Collection<TransferStatus> statuses,
                                                          LocalDateTime after);

    List<Transfer> findTop100ByFlowAndStatusAndStateEnteredAtBeforeOrderByStateEnteredAtAsc(
            String flow, TransferStatus status, LocalDateTime cutoff);

    long countByFlowAndStatus(String flow, TransferStatus status);
}