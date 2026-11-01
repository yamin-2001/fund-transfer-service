package com.banking.fundtransfer.saga;

import com.banking.fundtransfer.client.ClientPayloads;
import com.banking.fundtransfer.client.OtpClient;
import com.banking.fundtransfer.config.TransferProperties;
import com.banking.fundtransfer.dto.CashTransactionRequest;
import com.banking.fundtransfer.dto.ConfirmTransferRequest;
import com.banking.fundtransfer.dto.InitiateTransferRequest;
import com.banking.fundtransfer.dto.TransferStatusResponse;
import com.banking.fundtransfer.entity.Transfer;
import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.entity.TransferType;
import com.banking.fundtransfer.exception.InvalidTransferException;
import com.banking.fundtransfer.exception.OtpRejectedException;
import com.banking.fundtransfer.exception.TooManyPendingTransfersException;
import com.banking.fundtransfer.exception.TransferInProgressException;
import com.banking.fundtransfer.exception.TransferNotFoundException;
import com.banking.fundtransfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Service
public class TransferSagaService {

    private static final Set<TransferStatus> BEFORE_PAYMENT = EnumSet.of(
            TransferStatus.VALIDATING, TransferStatus.FEE_PENDING,
            TransferStatus.OTP_PENDING, TransferStatus.AWAITING_OTP);

    private final TransferRepository repository;
    private final TransferSagaEngine engine;
    private final OtpClient otpClient;
    private final TransferProperties properties;

    public TransferSagaService(TransferRepository repository,
                               TransferSagaEngine engine,
                               OtpClient otpClient,
                               TransferProperties properties) {
        this.repository = repository;
        this.engine = engine;
        this.otpClient = otpClient;
        this.properties = properties;
    }

    public TransferStatusResponse initiate(Long userId, String userEmail, InitiateTransferRequest request) {
        String from = request.fromAccountNumber().trim();
        String to = request.toAccountNumber().trim();
        BigDecimal amount = request.amount().setScale(2);

        if (from.equals(to)) {
            throw new InvalidTransferException("Cannot transfer to the same account");
        }
        checkLimit(userId);

        // Ownership, currency and the fee are checked later by Account and Charge Fee, through events.
        return toResponse(engine.start(userId, userEmail, from, to, amount));
    }

    /** Starts a cash deposit or withdrawal on one of the caller's own accounts. */
    public TransferStatusResponse initiateCash(Long userId, String userEmail,
                                               CashTransactionRequest request, TransferType type) {
        checkLimit(userId);
        String number = request.accountNumber().trim();
        BigDecimal amount = request.amount().setScale(2);

        // Ownership and status are checked later by Account, through events.
        return toResponse(engine.startCash(userId, userEmail, number, amount, type));
    }

    private void checkLimit(Long userId) {
        long open = repository.countByUserIdAndFlowAndStatusInAndCreatedAtAfter(
                userId, TransferSagaEngine.FLOW, BEFORE_PAYMENT,
                LocalDateTime.now().minusMinutes(properties.pendingExpiryMinutes()));
        if (open >= properties.maxPendingPerUser()) {
            throw new TooManyPendingTransfersException(
                    "You have too many unconfirmed transfers. Confirm or wait for them to expire");
        }
    }

    /** True when this transfer belongs to the caller and runs as a saga. */
    public boolean isSagaTransfer(Long userId, String transferId) {
        return repository.findByTransferIdAndUserId(transferId.trim(), userId)
                .map(Transfer::isSaga)
                .orElse(false);
    }

    public TransferStatusResponse get(Long userId, String transferId) {
        return toResponse(find(userId, transferId));
    }

    public TransferStatusResponse confirm(Long userId, String transferId, ConfirmTransferRequest request) {
        Transfer transfer = find(userId, transferId);

        switch (transfer.getStatus()) {
            case VALIDATING, FEE_PENDING -> throw new TransferInProgressException(
                    "Your request is still being checked, please wait a moment");
            case OTP_PENDING -> throw new TransferInProgressException(
                    "The one-time password is still being sent, please wait a moment");
            case AWAITING_OTP -> {
                return verifyAndStart(transfer, request.otp());
            }
            default -> {
                // Already moving, or finished: repeating a confirm only reports the current state.
                return toResponse(transfer);
            }
        }
    }

    public TransferStatusResponse resendOtp(Long userId, String transferId) {
        Transfer transfer = find(userId, transferId);
        if (!transfer.isSaga()) {
            throw new InvalidTransferException("This transfer does not support a new code");
        }
        return toResponse(engine.requestOtpResend(transfer.getTransferId()));
    }

    private TransferStatusResponse verifyAndStart(Transfer transfer, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new OtpRejectedException("OTP is required");
        }
        if (transfer.getOtpId() == null) {
            throw new TransferInProgressException("The one-time password is still being sent, please wait a moment");
        }

        // Checking the code stays synchronous: the user waits for the answer.
        ClientPayloads.OtpVerification result =
                otpClient.verifyOtp(transfer.getOtpId(), otp.trim(), transfer.getTransferId());
        if (!result.verified()) {
            throw new OtpRejectedException(result.message());
        }

        return toResponse(engine.otpVerified(transfer.getTransferId()));
    }

    private Transfer find(Long userId, String transferId) {
        return repository.findByTransferIdAndUserId(transferId.trim(), userId)
                .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
    }

    // ------------------------------------------------------------------ mapping

    static TransferStatusResponse toResponse(Transfer t) {
        TransferStatus status = t.getStatus();
        // A cash deposit or withdrawal never has a fee. A transfer learns its fee after the fee step.
        boolean feeKnown = !t.isSaga()
                || t.getType() != TransferType.TRANSFER
                || (status != TransferStatus.VALIDATING && status != TransferStatus.FEE_PENDING);
        BigDecimal fee = feeKnown ? t.getFee() : null;
        BigDecimal total = fee == null ? null : t.getAmount().add(fee);

        return new TransferStatusResponse(
                t.getTransferId(),
                t.getType().name(),
                status.name(),
                isTerminal(status),
                messageFor(t),
                t.getFailureCode(),
                t.getFromAccountNumber(),
                t.getToAccountNumber(),
                t.getAmount(),
                fee,
                total,
                t.getOtpExpiresAt(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    /** The screen can stop polling. RECONCILIATION_REQUIRED stops polling too, but a person must finish it. */
    private static boolean isTerminal(TransferStatus status) {
        return TransferStateMachine.isFinished(status) || status == TransferStatus.RECONCILIATION_REQUIRED;
    }

    private static String completedMessage(TransferType type) {
        return switch (type) {
            case DEPOSIT -> "Deposit completed";
            case WITHDRAWAL -> "Withdrawal completed";
            case TRANSFER -> "Transfer completed";
        };
    }

    private static String messageFor(Transfer t) {
        String reason = t.getFailureReason();
        TransferType type = t.getType();
        return switch (t.getStatus()) {
            case VALIDATING -> type == TransferType.TRANSFER ? "Checking your accounts..." : "Checking your account...";
            case FEE_PENDING -> "Calculating the fee...";
            case OTP_PENDING -> "Sending your one-time password...";
            case AWAITING_OTP -> "Enter the one-time password we sent to your email";
            case WITHDRAWING -> "Taking the money from your account...";
            case DEPOSITING -> type == TransferType.DEPOSIT
                    ? "Adding the money to your account..."
                    : "Sending the money to the receiver...";
            case REVERSING -> "The receiver could not be credited. Returning your money...";
            case COMPLETED, SUCCESS -> completedMessage(type);
            case FAILED -> reason == null ? "The request failed" : reason;
            case REVERSED -> "The transfer could not be completed and your money was returned"
                    + (reason == null ? "" : ": " + reason);
            case EXPIRED -> "The request expired. Please start a new one";
            case RECONCILIATION_REQUIRED -> "We are checking this request. Your money is safe. "
                    + "Please do not repeat it";
            case PENDING_OTP -> "Waiting for the one-time password";
            case PROCESSING -> "Processing...";
            case UNCONFIRMED -> "The result is not confirmed yet. Please try again";
        };
    }
}