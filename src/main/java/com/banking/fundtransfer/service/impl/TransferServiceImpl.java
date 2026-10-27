package com.banking.fundtransfer.service.impl;

import com.banking.fundtransfer.client.AccountClient;
import com.banking.fundtransfer.client.AccountInfo;
import com.banking.fundtransfer.client.CbsClient;
import com.banking.fundtransfer.client.CbsPostingResult;
import com.banking.fundtransfer.client.ChargeFeeClient;
import com.banking.fundtransfer.client.ClientPayloads;
import com.banking.fundtransfer.client.NotificationClient;
import com.banking.fundtransfer.client.OtpClient;
import com.banking.fundtransfer.config.TransferProperties;
import com.banking.fundtransfer.dto.ConfirmTransferRequest;
import com.banking.fundtransfer.dto.ConfirmTransferResponse;
import com.banking.fundtransfer.dto.InitiateTransferRequest;
import com.banking.fundtransfer.dto.InitiateTransferResponse;
import com.banking.fundtransfer.dto.TransferResponse;
import com.banking.fundtransfer.entity.Transfer;
import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.exception.AccountNotFoundException;
import com.banking.fundtransfer.exception.DownstreamUnavailableException;
import com.banking.fundtransfer.exception.InsufficientBalanceException;
import com.banking.fundtransfer.exception.InvalidTransferException;
import com.banking.fundtransfer.exception.NotAccountOwnerException;
import com.banking.fundtransfer.exception.OtpRejectedException;
import com.banking.fundtransfer.exception.ResultUnconfirmedException;
import com.banking.fundtransfer.exception.TooManyPendingTransfersException;
import com.banking.fundtransfer.exception.TransferExpiredException;
import com.banking.fundtransfer.exception.TransferInProgressException;
import com.banking.fundtransfer.exception.TransferNotFoundException;
import com.banking.fundtransfer.repository.TransferRepository;
import com.banking.fundtransfer.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.banking.fundtransfer.dto.CashTransactionRequest;
import com.banking.fundtransfer.entity.TransferType;

@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);
    private static final String CASH = "CASH";

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final ChargeFeeClient chargeFeeClient;
    private final OtpClient otpClient;
    private final CbsClient cbsClient;
    private final NotificationClient notificationClient;
    private final TransferProperties properties;

    public TransferServiceImpl(TransferRepository transferRepository,
    AccountClient accountClient,
    ChargeFeeClient chargeFeeClient,
    OtpClient otpClient,
    CbsClient cbsClient,
    NotificationClient notificationClient,
    TransferProperties properties) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.chargeFeeClient = chargeFeeClient;
        this.otpClient = otpClient;
        this.cbsClient = cbsClient;
        this.notificationClient = notificationClient;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ initiate

    @Override
    public InitiateTransferResponse initiate(Long userId, String userEmail, InitiateTransferRequest request) {
        String from = request.fromAccountNumber().trim();
        String to = request.toAccountNumber().trim();
        BigDecimal amount = request.amount().setScale(2);

        if (from.equals(to)) {
            throw new InvalidTransferException("Cannot transfer to the same account");
        }

        LocalDateTime now = LocalDateTime.now();
        long pending = transferRepository.countByUserIdAndStatusAndCreatedAtAfter(
                userId, TransferStatus.PENDING_OTP, now.minusMinutes(properties.pendingExpiryMinutes()));
        if (pending >= properties.maxPendingPerUser()) {
            throw new TooManyPendingTransfersException(
                    "You have too many unconfirmed transfers. Confirm or wait for them to expire");
        }

        // Ownership and account checks come from Account Service, never from the client.
        Map<String, AccountInfo> accounts = accountClient.findByNumbers(List.of(from, to));
        AccountInfo fromAccount = accounts.get(from);
        AccountInfo toAccount = accounts.get(to);
        if (fromAccount == null) {
            throw new AccountNotFoundException("Account not found: " + from);
        }
        if (toAccount == null) {
            throw new AccountNotFoundException("Account not found: " + to);
        }
        if (!userId.equals(fromAccount.userId())) {
            throw new NotAccountOwnerException("You do not own the source account");
        }
        if (!"ACTIVE".equals(fromAccount.status()) || !"ACTIVE".equals(toAccount.status())) {
            throw new InvalidTransferException("Account is not active");
        }
        if (!fromAccount.currency().equals(toAccount.currency())) {
            throw new InvalidTransferException("Currency mismatch between accounts");
        }

        // Own account = no fee, other customer's account = fee. Charge Fee applies the rule.
        boolean sameOwner = userId.equals(toAccount.userId());
        BigDecimal fee = chargeFeeClient.calculateFee(from, to, amount, sameOwner);
        BigDecimal totalDebit = amount.add(fee);

        // Early check so the user is not sent an OTP for a transfer that cannot work.
        // CBS checks again when it posts, so this is only a courtesy.
        if (cbsClient.getBalance(from).compareTo(totalDebit) < 0) {
            throw new InsufficientBalanceException("Insufficient balance");
        }

        Transfer transfer = new Transfer();
        transfer.setTransferId("TXN-" + UUID.randomUUID().toString().replace("-", "")
            .substring(0, 16).toUpperCase());
        transfer.setUserId(userId);
        transfer.setType(TransferType.TRANSFER);
        transfer.setFromAccountNumber(from);
        transfer.setToAccountNumber(to);
        transfer.setAmount(amount);
        transfer.setFee(fee);
        transfer.setStatus(TransferStatus.PENDING_OTP);
        transfer.setOtpVerified(false);
        transferRepository.save(transfer);

        String otpId;
        try {
            otpId = otpClient.sendOtp(userId, notificationEmail(userEmail), transfer.getTransferId());
        } catch (DownstreamUnavailableException ex) {
            transferRepository.finish(transfer.getTransferId(), TransferStatus.FAILED,
                "Could not send OTP", null, LocalDateTime.now());
            throw ex;
        }
        transferRepository.setOtpId(transfer.getTransferId(), otpId, LocalDateTime.now());

        return new InitiateTransferResponse(
                transfer.getTransferId(), fee, totalDebit, "PENDING_OTP", "OTP sent to your email");
    }

    // ------------------------------------------------------- deposit and withdraw

    @Override
    public InitiateTransferResponse initiateDeposit(Long userId, String userEmail,
                                                    CashTransactionRequest request) {
        return initiateCash(userId, userEmail, request, TransferType.DEPOSIT);
    }

    @Override
    public InitiateTransferResponse initiateWithdrawal(Long userId, String userEmail,
                                                       CashTransactionRequest request) {
        return initiateCash(userId, userEmail, request, TransferType.WITHDRAWAL);
    }

    private InitiateTransferResponse initiateCash(Long userId, String userEmail,
                                                  CashTransactionRequest request, TransferType type) {
        boolean deposit = type == TransferType.DEPOSIT;
        String number = request.accountNumber().trim();
        BigDecimal amount = request.amount().setScale(2);

        LocalDateTime now = LocalDateTime.now();
        long pending = transferRepository.countByUserIdAndStatusAndCreatedAtAfter(
                userId, TransferStatus.PENDING_OTP, now.minusMinutes(properties.pendingExpiryMinutes()));
        if (pending >= properties.maxPendingPerUser()) {
            throw new TooManyPendingTransfersException(
                    "You have too many unconfirmed transfers. Confirm or wait for them to expire");
        }

        // Ownership comes from Account Service, never from the client.
        AccountInfo account = accountClient.findByNumbers(List.of(number)).get(number);
        if (account == null) {
            throw new AccountNotFoundException("Account not found: " + number);
        }
        if (!userId.equals(account.userId())) {
            throw new NotAccountOwnerException("You do not own this account");
        }
        if (!"ACTIVE".equals(account.status())) {
            throw new InvalidTransferException("Account is not active");
        }

        // Early check so the user is not sent an OTP for a withdrawal that cannot work.
        // CBS checks again when it posts, so this is only a courtesy.
        if (!deposit && cbsClient.getBalance(number).compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance");
        }

        BigDecimal noFee = BigDecimal.ZERO.setScale(2);

        Transfer transfer = new Transfer();
        transfer.setTransferId("TXN-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase());
        transfer.setUserId(userId);
        transfer.setType(type);
        transfer.setFromAccountNumber(deposit ? CASH : number);
        transfer.setToAccountNumber(deposit ? number : CASH);
        transfer.setAmount(amount);
        transfer.setFee(noFee);
        transfer.setStatus(TransferStatus.PENDING_OTP);
        transfer.setOtpVerified(false);
        transferRepository.save(transfer);

        String otpId;
        try {
            otpId = otpClient.sendOtp(userId, notificationEmail(userEmail), transfer.getTransferId());
        } catch (DownstreamUnavailableException ex) {
            transferRepository.finish(transfer.getTransferId(), TransferStatus.FAILED,
                    "Could not send OTP", null, LocalDateTime.now());
            throw ex;
        }
        transferRepository.setOtpId(transfer.getTransferId(), otpId, LocalDateTime.now());

        // For a deposit, totalDebit is the amount that will be credited to the account.
        return new InitiateTransferResponse(
                transfer.getTransferId(), noFee, amount, "PENDING_OTP", "OTP sent to your email");
    }
    // ------------------------------------------------------------------- confirm

    @Override
    public ConfirmTransferResponse confirm(Long userId, String userEmail, String transferId,
        ConfirmTransferRequest request) {
        Transfer transfer = transferRepository.findByTransferIdAndUserId(transferId.trim(), userId)
        .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));

        return switch (transfer.getStatus()) {
            case SUCCESS -> new ConfirmTransferResponse(
            transfer.getTransferId(), "SUCCESS", "Transfer completed");
            case FAILED -> new ConfirmTransferResponse(
            transfer.getTransferId(), "FAILED",
            transfer.getFailureReason() == null ? "Transfer failed" : transfer.getFailureReason());
            case EXPIRED -> throw new TransferExpiredException("Transfer expired, please start a new one");
            case PROCESSING -> throw new TransferInProgressException("Transfer is already being processed");
            case PENDING_OTP -> confirmWithOtp(transfer, userEmail, request.otp());
            case UNCONFIRMED -> retryPosting(transfer, userEmail);
        };
    }

    private ConfirmTransferResponse confirmWithOtp(Transfer transfer, String userEmail, String otp) {
        String id = transfer.getTransferId();
        Long userId = transfer.getUserId();

        if (otp == null || otp.isBlank()) {
            throw new OtpRejectedException("OTP is required");
        }
        if (transfer.getOtpId() == null) {
            throw new OtpRejectedException("No OTP was issued for this transfer. Please start a new transfer");
        }
        if (transfer.getCreatedAt().isBefore(
                LocalDateTime.now().minusMinutes(properties.pendingExpiryMinutes()))) {
            transferRepository.transition(id, userId, TransferStatus.PENDING_OTP,
                TransferStatus.EXPIRED, LocalDateTime.now());
            throw new TransferExpiredException("Transfer expired, please start a new one");
        }

        // Claim the transfer. If another request got there first, this returns 0.
        if (transferRepository.transition(id, userId, TransferStatus.PENDING_OTP,
                TransferStatus.PROCESSING, LocalDateTime.now()) == 0) {
            throw new TransferInProgressException("Transfer is already being processed");
        }

        ClientPayloads.OtpVerification verification;
        try {
            verification = otpClient.verifyOtp(transfer.getOtpId(), otp, id);
        } catch (DownstreamUnavailableException ex) {
            // OTP outcome unknown: put it back so the user can try again.
            transferRepository.transition(id, userId, TransferStatus.PROCESSING,
                TransferStatus.PENDING_OTP, LocalDateTime.now());
            throw ex;
        }

        if (!verification.verified()) {
            transferRepository.transition(id, userId, TransferStatus.PROCESSING,
                TransferStatus.PENDING_OTP, LocalDateTime.now());
            throw new OtpRejectedException(verification.message());
        }

        // Recorded BEFORE touching CBS, so a retry after a crash never asks for the OTP again.
        transferRepository.markOtpVerified(id, LocalDateTime.now());
        return post(transfer, userEmail);
    }

    private ConfirmTransferResponse retryPosting(Transfer transfer, String userEmail) {
        if (!transfer.isOtpVerified()) {
            throw new InvalidTransferException("Transfer is in an invalid state, please start a new one");
        }
        if (transferRepository.transition(transfer.getTransferId(), transfer.getUserId(),
                TransferStatus.UNCONFIRMED, TransferStatus.PROCESSING, LocalDateTime.now()) == 0) {
            throw new TransferInProgressException("Transfer is already being processed");
        }
        return post(transfer, userEmail);
    }

    private ConfirmTransferResponse post(Transfer transfer, String userEmail) {
        String id = transfer.getTransferId();

        CbsPostingResult result = postToCbs(transfer);
        if (result.outcome() == CbsPostingResult.Outcome.UNKNOWN) {
            // CBS ignores a repeated referenceId, so asking again can never move money twice.
            result = postToCbs(transfer);
        }

        String recipient = notificationEmail(userEmail);

        if (result.outcome() == CbsPostingResult.Outcome.POSTED) {
            transferRepository.finish(id, TransferStatus.SUCCESS, null,
                result.transactionId(), LocalDateTime.now());
            notificationClient.sendTransferSuccess(recipient, id, transfer.getFromAccountNumber(),
                transfer.getToAccountNumber(), transfer.getAmount(), transfer.getFee());
            return new ConfirmTransferResponse(id, "SUCCESS", "Transfer completed");
        }

        if (result.outcome() == CbsPostingResult.Outcome.REJECTED) {
            String reason = truncate(result.message());
            transferRepository.finish(id, TransferStatus.FAILED, reason, null, LocalDateTime.now());
            notificationClient.sendTransferFailed(recipient, transfer.getFromAccountNumber(),
                transfer.getToAccountNumber(), transfer.getAmount(), reason);
            return new ConfirmTransferResponse(id, "FAILED", reason);
        }

        // Still unknown: do NOT mark it failed, the money may have moved.
        transferRepository.transition(id, transfer.getUserId(), TransferStatus.PROCESSING,
            TransferStatus.UNCONFIRMED, LocalDateTime.now());
        log.warn("Transfer {} left UNCONFIRMED, CBS outcome unknown", id);
        throw new ResultUnconfirmedException(
                "We could not confirm the result yet. Press Confirm again in a moment, "
                        + "or check History. Do not start a new transfer.");
    }
    private CbsPostingResult postToCbs(Transfer transfer) {
        return switch (transfer.getType()) {
            case DEPOSIT -> cbsClient.postDeposit(
                    transfer.getTransferId(),
                    transfer.getToAccountNumber(),
                    transfer.getAmount());
            case WITHDRAWAL -> cbsClient.postWithdrawal(
                    transfer.getTransferId(),
                    transfer.getFromAccountNumber(),
                    transfer.getAmount());
            case TRANSFER -> cbsClient.postTransfer(
                    transfer.getTransferId(),
                    transfer.getFromAccountNumber(),
                    transfer.getToAccountNumber(),
                    transfer.getAmount(),
                    transfer.getFee());
        };
    }

    // ---------------------------------------------------------------------- list

    @Override
    public List<TransferResponse> list(Long userId) {
        return transferRepository.findTop100ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new TransferResponse(
                        t.getTransferId(),
                        t.getType().name(),
                        t.getFromAccountNumber(),
                        t.getToAccountNumber(),
                        t.getAmount(),
                        t.getFee(),
                        t.getStatus().name(),
                        t.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------- helpers

    private String notificationEmail(String userEmail) {
        String configured = properties.notifyEmail();
        if (configured == null || configured.isBlank() || configured.equalsIgnoreCase("USER")) {
            return userEmail;
        }
        return configured.trim();
    }

    private String truncate(String text) {
        if (text == null) {
            return "Transfer rejected";
        }
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}