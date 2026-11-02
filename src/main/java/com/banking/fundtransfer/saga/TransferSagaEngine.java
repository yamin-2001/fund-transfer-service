package com.banking.fundtransfer.saga;

import com.banking.fundtransfer.config.SagaProperties;
import com.banking.fundtransfer.config.TransferProperties;
import com.banking.fundtransfer.entity.Transfer;
import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.entity.TransferType;
import com.banking.fundtransfer.exception.InvalidTransferException;
import com.banking.fundtransfer.exception.ResendTooSoonException;
import com.banking.fundtransfer.exception.TransferNotFoundException;
import com.banking.fundtransfer.messaging.EventEnvelope;
import com.banking.fundtransfer.messaging.EventPublisher;
import com.banking.fundtransfer.messaging.InvalidEventException;
import com.banking.fundtransfer.messaging.ProcessedEventGuard;
import com.banking.fundtransfer.messaging.TopicNames;
import com.banking.fundtransfer.messaging.payload.BalanceCheckedEvent;
import com.banking.fundtransfer.messaging.payload.CalculateFeePayload;
import com.banking.fundtransfer.messaging.payload.CheckBalancePayload;
import com.banking.fundtransfer.messaging.payload.CashAccountValidatedEvent;
import com.banking.fundtransfer.messaging.payload.DepositCompletedEvent;
import com.banking.fundtransfer.messaging.payload.DepositPayload;
import com.banking.fundtransfer.messaging.payload.FailureEvent;
import com.banking.fundtransfer.messaging.payload.FeeCalculatedEvent;
import com.banking.fundtransfer.messaging.payload.IssueOtpPayload;
import com.banking.fundtransfer.messaging.payload.OtpDeliveryFailedEvent;
import com.banking.fundtransfer.messaging.payload.OtpIssuedEvent;
import com.banking.fundtransfer.messaging.payload.ReconciliationRequiredPayload;
import com.banking.fundtransfer.messaging.payload.ReversalCompletedEvent;
import com.banking.fundtransfer.messaging.payload.ReversalPayload;
import com.banking.fundtransfer.messaging.payload.TransferOutcomePayload;
import com.banking.fundtransfer.messaging.payload.TransferValidatedEvent;
import com.banking.fundtransfer.messaging.payload.ValidateCashAccountPayload;
import com.banking.fundtransfer.messaging.payload.ValidateTransferPayload;
import com.banking.fundtransfer.messaging.payload.WithdrawCompletedEvent;
import com.banking.fundtransfer.messaging.payload.WithdrawPayload;
import com.banking.fundtransfer.repository.TransferRepository;
import com.banking.fundtransfer.service.NotificationRecipient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferSagaEngine {

    private static final Logger log = LoggerFactory.getLogger(TransferSagaEngine.class);

    public static final String FLOW = "SAGA";

    /** Failure code of a transaction that was escalated only because CBS did not answer in time. It can heal itself. */
    public static final String STEP_TIMEOUT = "STEP_TIMEOUT";

    /** The states the timeout sweeper looks at. */
    public static final List<TransferStatus> WATCHED = List.of(
            TransferStatus.VALIDATING, TransferStatus.FEE_PENDING, TransferStatus.BALANCE_CHECKING,
            TransferStatus.OTP_PENDING, TransferStatus.AWAITING_OTP, TransferStatus.WITHDRAWING,
            TransferStatus.DEPOSITING, TransferStatus.REVERSING);

    private static final String HANDLER = "saga-events";
    private static final String CASH = "CASH";
    private static final String TRANSIT = "TRANSIT";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final TransferRepository repository;
    private final ProcessedEventGuard guard;
    private final EventPublisher events;
    private final TransferProperties transferProperties;
    private final SagaProperties sagaProperties;
    private final MeterRegistry meters;

    public TransferSagaEngine(TransferRepository repository,
                              ProcessedEventGuard guard,
                              EventPublisher events,
                              TransferProperties transferProperties,
                              SagaProperties sagaProperties,
                              MeterRegistry meters) {
        this.repository = repository;
        this.guard = guard;
        this.events = events;
        this.transferProperties = transferProperties;
        this.sagaProperties = sagaProperties;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ start

    @Transactional
    public Transfer start(Long userId, String userEmail, String from, String to, BigDecimal amount) {
        Transfer transfer = newSagaTransfer(userId, userEmail, TransferType.TRANSFER, from, to, amount);
        Transfer saved = repository.save(transfer);
        publishValidate(saved, null);
        meters.counter("transfers.transitions", "from", "NONE", "to", "VALIDATING").increment();
        return saved;
    }

    /** Starts a cash deposit or withdrawal on the user's own account. */
    @Transactional
    public Transfer startCash(Long userId, String userEmail, String accountNumber,
                              BigDecimal amount, TransferType type) {
        if (type == TransferType.TRANSFER) {
            throw new IllegalArgumentException("startCash is only for deposits and withdrawals");
        }
        boolean deposit = type == TransferType.DEPOSIT;
        Transfer transfer = newSagaTransfer(userId, userEmail, type,
                deposit ? CASH : accountNumber,
                deposit ? accountNumber : CASH,
                amount);
        Transfer saved = repository.save(transfer);
        publishValidate(saved, null);
        meters.counter("transfers.transitions", "from", "NONE", "to", "VALIDATING").increment();
        return saved;
    }

    private Transfer newSagaTransfer(Long userId, String userEmail, TransferType type,
                                     String from, String to, BigDecimal amount) {
        Transfer transfer = new Transfer();
        transfer.setTransferId("TXN-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase());
        transfer.setUserId(userId);
        transfer.setUserEmail(userEmail);
        transfer.setType(type);
        transfer.setFlow(FLOW);
        transfer.setFromAccountNumber(from);
        transfer.setToAccountNumber(to);
        transfer.setAmount(amount);
        transfer.setFee(ZERO);
        transfer.setOtpVerified(false);
        transfer.moveTo(TransferStatus.VALIDATING);
        return transfer;
    }

    // ------------------------------------------------------ events from others

    @Transactional
    public void onTransferValidated(EventEnvelope envelope, TransferValidatedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() != TransferType.TRANSFER || transfer.getStatus() != TransferStatus.VALIDATING) {
            ignored(envelope);
            return;
        }

        transfer.setSameOwner(event.sameOwner());
        transfer.setCurrency(event.currency());
        move(transfer, TransferStatus.FEE_PENDING);
        publishFee(transfer, envelope.eventId());
    }

    @Transactional
    public void onTransferRejected(EventEnvelope envelope, FailureEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() != TransferType.TRANSFER || transfer.getStatus() != TransferStatus.VALIDATING) {
            ignored(envelope);
            return;
        }
        fail(transfer, event.reasonCode(), event.reason(), envelope.eventId());
    }

    @Transactional
    public void onCashAccountValidated(EventEnvelope envelope, CashAccountValidatedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() == TransferType.TRANSFER || transfer.getStatus() != TransferStatus.VALIDATING) {
            ignored(envelope);
            return;
        }

        transfer.setCurrency(event.currency());

        if (transfer.getType() == TransferType.DEPOSIT) {
            // A deposit adds money, so there is no balance to check.
            if (!transferProperties.depositRequiresOtp()) {
                // A deposit needs no OTP: the request itself is the authorization.
                transfer.setOtpVerified(true);
                move(transfer, TransferStatus.DEPOSITING);
                publishDeposit(transfer, envelope.eventId());
                return;
            }
            transfer.setLastOtpRequestedAt(LocalDateTime.now());
            move(transfer, TransferStatus.OTP_PENDING);
            publishIssueOtp(transfer, envelope.eventId());
            return;
        }

        // The balance is checked before the code is asked for, so the user is
        // never sent a code for a transaction that cannot succeed.
        move(transfer, TransferStatus.BALANCE_CHECKING);
        publishCheckBalance(transfer, envelope.eventId());
    }

    @Transactional
    public void onCashAccountRejected(EventEnvelope envelope, FailureEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() == TransferType.TRANSFER || transfer.getStatus() != TransferStatus.VALIDATING) {
            ignored(envelope);
            return;
        }
        fail(transfer, event.reasonCode(), event.reason(), envelope.eventId());
    }

    @Transactional
    public void onFeeCalculated(EventEnvelope envelope, FeeCalculatedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() != TransferType.TRANSFER || transfer.getStatus() != TransferStatus.FEE_PENDING) {
            ignored(envelope);
            return;
        }

        BigDecimal fee = event.fee();
        if (fee == null || fee.signum() < 0 || fee.stripTrailingZeros().scale() > 2) {
            fail(transfer, "INVALID_FEE", "The fee service returned an invalid fee", envelope.eventId());
            return;
        }

        transfer.setFee(fee.setScale(2));

        // The total debit is only known now that the fee is known, so this is
        // the first moment the balance can be checked against it.
        move(transfer, TransferStatus.BALANCE_CHECKING);
        publishCheckBalance(transfer, envelope.eventId());
    }

    @Transactional
    public void onBalanceChecked(EventEnvelope envelope, BalanceCheckedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getStatus() != TransferStatus.BALANCE_CHECKING) {
            ignored(envelope);
            return;
        }

        transfer.setLastOtpRequestedAt(LocalDateTime.now());
        move(transfer, TransferStatus.OTP_PENDING);
        publishIssueOtp(transfer, envelope.eventId());
    }

    @Transactional
    public void onBalanceRejected(EventEnvelope envelope, FailureEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getStatus() != TransferStatus.BALANCE_CHECKING) {
            ignored(envelope);
            return;
        }
        // Nothing was moved, and no code was sent, so this is only shown on the screen.
        fail(transfer, event.reasonCode(), event.reason(), envelope.eventId());
    }

    @Transactional
    public void onOtpIssued(EventEnvelope envelope, OtpIssuedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (event.otpId() == null || event.otpId().isBlank()) {
            throw new InvalidEventException("OtpIssued has no otpId");
        }

        TransferStatus status = transfer.getStatus();
        if (status != TransferStatus.OTP_PENDING && status != TransferStatus.AWAITING_OTP) {
            ignored(envelope);
            return;
        }

        // A new code (first one, or a resend) replaces the previous otpId. The OTP service invalidated the old code.
        transfer.setOtpId(event.otpId());
        transfer.setOtpExpiresAt(event.expiresAt() == null
                ? null
                : LocalDateTime.ofInstant(event.expiresAt(), ZoneId.systemDefault()));
        if (status == TransferStatus.OTP_PENDING) {
            move(transfer, TransferStatus.AWAITING_OTP);
        }
    }

    @Transactional
    public void onOtpDeliveryFailed(EventEnvelope envelope, OtpDeliveryFailedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        TransferStatus status = transfer.getStatus();
        if (status != TransferStatus.OTP_PENDING && status != TransferStatus.AWAITING_OTP) {
            ignored(envelope);
            return;
        }
        fail(transfer, "OTP_DELIVERY_FAILED",
                "The one-time password could not be delivered. Please start again", envelope.eventId());
    }

    @Transactional
    public void onWithdrawCompleted(EventEnvelope envelope, WithdrawCompletedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() == TransferType.DEPOSIT
                || (transfer.getStatus() != TransferStatus.WITHDRAWING && !healable(transfer))) {
            ignored(envelope);
            return;
        }
        if (!sameMoney(transfer.getAmount(), event.amount()) || !sameMoney(transfer.getFee(), event.fee())) {
            escalate(transfer, "AMOUNT_MISMATCH",
                    "CBS reported different amounts for the withdrawal than the request", envelope.eventId());
            return;
        }

        clearFailure(transfer);
        if (transfer.getType() == TransferType.TRANSFER) {
            move(transfer, TransferStatus.DEPOSITING);
            publishDeposit(transfer, envelope.eventId());
        } else {
            // A cash withdrawal has no second step.
            move(transfer, TransferStatus.COMPLETED);
            publishOutcome("TransferCompleted", transfer, null, envelope.eventId());
            recordDuration(transfer, "completed");
        }
    }

    @Transactional
    public void onWithdrawFailed(EventEnvelope envelope, FailureEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() == TransferType.DEPOSIT
                || (transfer.getStatus() != TransferStatus.WITHDRAWING && !healable(transfer))) {
            ignored(envelope);
            return;
        }
        fail(transfer, event.reasonCode(), event.reason(), envelope.eventId());
    }

    @Transactional
    public void onDepositCompleted(EventEnvelope envelope, DepositCompletedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() == TransferType.WITHDRAWAL
                || (transfer.getStatus() != TransferStatus.DEPOSITING && !healable(transfer))) {
            ignored(envelope);
            return;
        }
        if (!sameMoney(transfer.getAmount(), event.amount())) {
            escalate(transfer, "AMOUNT_MISMATCH",
                    "CBS reported a different amount for the deposit than the request", envelope.eventId());
            return;
        }

        clearFailure(transfer);
        move(transfer, TransferStatus.COMPLETED);
        publishOutcome("TransferCompleted", transfer, null, envelope.eventId());
        recordDuration(transfer, "completed");
    }

    @Transactional
    public void onDepositFailed(EventEnvelope envelope, FailureEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() == TransferType.WITHDRAWAL
                || (transfer.getStatus() != TransferStatus.DEPOSITING && !healable(transfer))) {
            ignored(envelope);
            return;
        }

        if (transfer.getType() == TransferType.DEPOSIT) {
            // A cash deposit that CBS refused changed nothing in the ledger, so there is nothing to reverse.
            fail(transfer, event.reasonCode(), event.reason(), envelope.eventId());
            return;
        }

        // The receiver could not be credited. The sender gets everything back, including the fee.
        transfer.setFailureCode(truncate(event.reasonCode(), 50));
        transfer.setFailureReason(truncate(event.reason(), 255));
        move(transfer, TransferStatus.REVERSING);
        publishReversal(transfer, envelope.eventId());
    }

    @Transactional
    public void onReversalCompleted(EventEnvelope envelope, ReversalCompletedEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() != TransferType.TRANSFER
                || (transfer.getStatus() != TransferStatus.REVERSING && !healable(transfer))) {
            ignored(envelope);
            return;
        }

        String reason = STEP_TIMEOUT.equals(transfer.getFailureCode())
                ? "The transfer took too long and was reversed"
                : transfer.getFailureReason();
        transfer.setFailureReason(truncate(reason, 255));
        move(transfer, TransferStatus.REVERSED);
        publishOutcome("TransferReversed", transfer, transfer.getFailureReason(), envelope.eventId());
        recordDuration(transfer, "reversed");
    }

    @Transactional
    public void onReversalFailed(EventEnvelope envelope, FailureEvent event) {
        Transfer transfer = open(envelope, event.transferId()).orElse(null);
        if (transfer == null) {
            return;
        }
        if (transfer.getType() != TransferType.TRANSFER || transfer.getStatus() != TransferStatus.REVERSING) {
            ignored(envelope);
            return;
        }
        // The refund was refused. This can only be solved by a person.
        escalate(transfer, "REVERSAL_FAILED",
                "The refund was refused by CBS: " + event.reasonCode(), envelope.eventId());
    }

    // ---------------------------------------------------------- from the user

    /** The user's OTP was accepted. The money starts to move. */
    @Transactional
    public Transfer otpVerified(String transferId) {
        Transfer transfer = repository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));

        if (transfer.getStatus() != TransferStatus.AWAITING_OTP) {
            return transfer;
        }
        transfer.setOtpVerified(true);

        if (transfer.getType() == TransferType.DEPOSIT) {
            move(transfer, TransferStatus.DEPOSITING);
            publishDeposit(transfer, null);
        } else {
            move(transfer, TransferStatus.WITHDRAWING);
            publishWithdraw(transfer, null);
        }
        return transfer;
    }

    /** Asks the OTP service for a new code. The transaction stays in AWAITING_OTP. */
    @Transactional
    public Transfer requestOtpResend(String transferId) {
        Transfer transfer = repository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));

        if (transfer.getStatus() != TransferStatus.AWAITING_OTP) {
            throw new InvalidTransferException(
                    "A new code can only be requested while the transfer is waiting for the OTP");
        }

        LocalDateTime last = transfer.getLastOtpRequestedAt();
        if (last != null) {
            long wait = Duration.between(LocalDateTime.now(),
                    last.plusSeconds(sagaProperties.otpResendCooldownSeconds())).getSeconds();
            if (wait > 0) {
                throw new ResendTooSoonException("Please wait " + wait + " seconds before requesting a new code");
            }
        }

        transfer.setLastOtpRequestedAt(LocalDateTime.now());
        publishIssueOtp(transfer, null);
        return transfer;
    }

    // --------------------------------------------------------------- timeouts

    public int timeoutSeconds(TransferStatus status) {
        return switch (status) {
            case OTP_PENDING -> sagaProperties.otpIssueTimeoutSeconds();
            case AWAITING_OTP -> transferProperties.pendingExpiryMinutes() * 60;
            default -> sagaProperties.stepTimeoutSeconds();
        };
    }

    /** Called by the sweeper for a transaction that seems to be stuck. Safe to call twice. */
    @Transactional
    public void handleTimeout(String transferId, TransferStatus expected) {
        Optional<Transfer> found = repository.findByIdForUpdate(transferId).filter(Transfer::isSaga);
        if (found.isEmpty()) {
            return;
        }
        Transfer transfer = found.get();

        // Someone else may have moved it since the sweeper looked.
        if (transfer.getStatus() != expected || !isStale(transfer, expected)) {
            return;
        }

        switch (expected) {
            case VALIDATING -> retry(transfer, sagaProperties.maxResends(),
                    () -> publishValidate(transfer, null),
                    () -> fail(transfer, "VALIDATION_TIMEOUT",
                            "Your accounts could not be checked in time. Please try again", null));
            case FEE_PENDING -> retry(transfer, sagaProperties.maxResends(),
                    () -> publishFee(transfer, null),
                    () -> fail(transfer, "FEE_TIMEOUT",
                            "The fee could not be calculated in time. Please try again", null));
            case BALANCE_CHECKING -> retry(transfer, sagaProperties.maxResends(),
                    () -> publishCheckBalance(transfer, null),
                    () -> fail(transfer, "BALANCE_CHECK_TIMEOUT",
                            "Your balance could not be checked in time. Please try again", null));
            case OTP_PENDING -> fail(transfer, "OTP_TIMEOUT",
                    "The one-time password could not be sent in time. Please try again", null);
            case AWAITING_OTP -> {
                move(transfer, TransferStatus.EXPIRED);
                recordDuration(transfer, "expired");
            }
            case WITHDRAWING -> retry(transfer, sagaProperties.maxResends(),
                    () -> publishWithdraw(transfer, null),
                    () -> escalate(transfer, STEP_TIMEOUT, "The withdrawal was not confirmed in time", null));
            case DEPOSITING -> retry(transfer, sagaProperties.maxResends(),
                    () -> publishDeposit(transfer, null),
                    () -> escalate(transfer, STEP_TIMEOUT, "The deposit was not confirmed in time", null));
            case REVERSING -> retry(transfer, sagaProperties.reversalMaxResends(),
                    () -> publishReversal(transfer, null),
                    () -> escalate(transfer, STEP_TIMEOUT, "The reversal was not confirmed in time", null));
            default -> {
                // Other states are never watched.
            }
        }
    }

    private void retry(Transfer transfer, int maxResends, Runnable resend, Runnable exhausted) {
        if (transfer.getStepAttempts() < maxResends) {
            transfer.recordResend();
            resend.run();
            meters.counter("transfers.resends", "state", transfer.getStatus().name()).increment();
            log.warn("Transfer {} had no answer in {}, command sent again ({} of {})",
                    transfer.getTransferId(), transfer.getStatus(), transfer.getStepAttempts(), maxResends);
        } else {
            exhausted.run();
        }
    }

    private boolean isStale(Transfer transfer, TransferStatus status) {
        LocalDateTime since = transfer.getStateEnteredAt();
        return since != null && since.isBefore(LocalDateTime.now().minusSeconds(timeoutSeconds(status)));
    }

    // ----------------------------------------------------------- state changes

    private void fail(Transfer transfer, String code, String reason, String causationId) {
        transfer.setFailureCode(truncate(code, 50));
        transfer.setFailureReason(truncate(reason, 255));
        move(transfer, TransferStatus.FAILED);

        // Only a transaction the user already authorized needs an email. Earlier failures are shown on the screen.
        if (transfer.isOtpVerified()) {
            publishOutcome("TransferFailed", transfer, transfer.getFailureReason(), causationId);
        }
        recordDuration(transfer, "failed");
    }

    private void escalate(Transfer transfer, String code, String details, String causationId) {
        TransferStatus before = transfer.getStatus();
        transfer.setFailureCode(truncate(code, 50));
        transfer.setFailureReason(truncate(details, 255));
        if (before != TransferStatus.RECONCILIATION_REQUIRED) {
            move(transfer, TransferStatus.RECONCILIATION_REQUIRED);
        }

        events.publish(TopicNames.TRANSFER_EVENTS, transfer.getTransferId(), "ReconciliationRequired",
                transfer.getTransferId(), causationId,
                new ReconciliationRequiredPayload(transfer.getTransferId(), before.name(), details));
        meters.counter("transfers.reconciliation.escalations", "code", code).increment();
        log.error("Transfer {} needs reconciliation ({}): {}", transfer.getTransferId(), code, details);
    }

    private void move(Transfer transfer, TransferStatus next) {
        TransferStatus from = transfer.getStatus();
        if (!TransferStateMachine.canMove(transfer.getType(), from, next)) {
            throw new IllegalStateException("Illegal " + transfer.getType() + " move " + from + " -> " + next);
        }
        transfer.moveTo(next);
        meters.counter("transfers.transitions", "from", from.name(), "to", next.name()).increment();
    }

    /** A transaction that waits only because CBS did not answer in time. A late answer may settle it. */
    private boolean healable(Transfer transfer) {
        return transfer.getStatus() == TransferStatus.RECONCILIATION_REQUIRED
                && STEP_TIMEOUT.equals(transfer.getFailureCode());
    }

    private void clearFailure(Transfer transfer) {
        transfer.setFailureCode(null);
        transfer.setFailureReason(null);
    }

    private boolean sameMoney(BigDecimal expected, BigDecimal actual) {
        return expected != null && actual != null && expected.compareTo(actual) == 0;
    }

    // ---------------------------------------------------------------- commands

    private void publishValidate(Transfer t, String causationId) {
        if (t.getType() == TransferType.TRANSFER) {
            events.publish(TopicNames.ACCOUNT_COMMANDS, t.getTransferId(), "ValidateTransfer", t.getTransferId(),
                    causationId, new ValidateTransferPayload(t.getTransferId(), t.getUserId(),
                            t.getFromAccountNumber(), t.getToAccountNumber(), t.getAmount()));
        } else {
            String operation = t.getType() == TransferType.DEPOSIT ? "DEPOSIT" : "WITHDRAWAL";
            events.publish(TopicNames.ACCOUNT_COMMANDS, t.getTransferId(), "ValidateCashAccount",
                    t.getTransferId(), causationId, new ValidateCashAccountPayload(
                            t.getTransferId(), t.getUserId(), ownAccount(t), operation, t.getAmount()));
        }
    }

    private void publishFee(Transfer t, String causationId) {
        events.publish(TopicNames.FEE_COMMANDS, t.getTransferId(), "CalculateFee", t.getTransferId(),
                causationId, new CalculateFeePayload(t.getTransferId(), t.getFromAccountNumber(),
                        t.getToAccountNumber(), t.getAmount(), Boolean.TRUE.equals(t.getSameOwner())));
    }

    /**
     * Asks the banking core whether the account can cover the debit.
     *
     * <p>The fee of a cash transaction is zero, so the required amount is the
     * amount itself. The check moves no money, so CBS answers every resend
     * again with the balance of that moment instead of keeping the first
     * answer.
     */
    private void publishCheckBalance(Transfer t, String causationId) {
        BigDecimal fee = t.getType() == TransferType.TRANSFER
                ? (t.getFee() == null ? ZERO : t.getFee())
                : ZERO;
        events.publish(TopicNames.CBS_COMMANDS, t.getTransferId(), "CheckBalance", t.getTransferId(),
                causationId, new CheckBalancePayload(t.getTransferId(),
                        t.getTransferId() + ":CHECK_BALANCE", ownAccount(t), t.getAmount(), fee));
    }

    private void publishIssueOtp(Transfer t, String causationId) {
        events.publish(TopicNames.OTP_COMMANDS, t.getTransferId(), "IssueOtp", t.getTransferId(),
                causationId, new IssueOtpPayload(t.getTransferId(), t.getUserId(), recipient(t), "TRANSFER"));
    }

    private void publishWithdraw(Transfer t, String causationId) {
        boolean transfer = t.getType() == TransferType.TRANSFER;
        events.publish(TopicNames.CBS_COMMANDS, t.getTransferId(), "Withdraw", t.getTransferId(),
                causationId, new WithdrawPayload(t.getTransferId(), t.getTransferId() + ":WITHDRAW",
                        t.getFromAccountNumber(), t.getAmount(), transfer ? t.getFee() : ZERO,
                        transfer ? TRANSIT : CASH));
    }

    private void publishDeposit(Transfer t, String causationId) {
        boolean transfer = t.getType() == TransferType.TRANSFER;
        events.publish(TopicNames.CBS_COMMANDS, t.getTransferId(), "Deposit", t.getTransferId(),
                causationId, new DepositPayload(t.getTransferId(), t.getTransferId() + ":DEPOSIT",
                        t.getToAccountNumber(), t.getAmount(), transfer ? TRANSIT : CASH));
    }

    private void publishReversal(Transfer t, String causationId) {
        events.publish(TopicNames.CBS_COMMANDS, t.getTransferId(), "Reversal", t.getTransferId(),
                causationId, new ReversalPayload(t.getTransferId(), t.getTransferId() + ":REVERSAL",
                        t.getFromAccountNumber(), t.getAmount(), t.getFee()));
    }

    private void publishOutcome(String eventType, Transfer t, String reason, String causationId) {
        events.publish(TopicNames.TRANSFER_EVENTS, t.getTransferId(), eventType, t.getTransferId(),
                causationId, new TransferOutcomePayload(t.getTransferId(), t.getType().name(), t.getUserId(),
                        recipient(t), t.getFromAccountNumber(), t.getToAccountNumber(),
                        t.getAmount(), t.getFee(), reason));
    }

    /** The user's own account in a cash deposit or withdrawal. */
    private String ownAccount(Transfer t) {
        return t.getType() == TransferType.DEPOSIT ? t.getToAccountNumber() : t.getFromAccountNumber();
    }

    /** The demo requirement: emails go to the configured address, or to the user when set to USER. */
    private String recipient(Transfer t) {
        return NotificationRecipient.resolve(transferProperties.notifyEmail(), t.getUserEmail());
    }

    // ------------------------------------------------------------------- helpers

    /** Records the event as handled, and finds and locks the transfer. Empty means: nothing to do. */
    private Optional<Transfer> open(EventEnvelope envelope, String transferId) {
        if (transferId == null || transferId.isBlank()) {
            throw new InvalidEventException(envelope.eventType() + ": transferId is missing");
        }
        if (!guard.firstTime(HANDLER, envelope.eventId())) {
            count(envelope.eventType(), "duplicate");
            return Optional.empty();
        }
        Optional<Transfer> found = repository.findByIdForUpdate(transferId.trim()).filter(Transfer::isSaga);
        if (found.isEmpty()) {
            count(envelope.eventType(), "unknown");
        }
        return found;
    }

    private void ignored(EventEnvelope envelope) {
        count(envelope.eventType(), "ignored");
    }

    private void count(String eventType, String result) {
        meters.counter("saga.events", "type", eventType, "result", result).increment();
    }

    private void recordDuration(Transfer transfer, String outcome) {
        if (transfer.getCreatedAt() != null) {
            meters.timer("transfers.duration", "outcome", outcome)
                    .record(Duration.between(transfer.getCreatedAt(), LocalDateTime.now()));
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}