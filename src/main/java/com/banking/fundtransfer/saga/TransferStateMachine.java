package com.banking.fundtransfer.saga;

import com.banking.fundtransfer.entity.TransferStatus;
import com.banking.fundtransfer.entity.TransferType;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.banking.fundtransfer.entity.TransferStatus.AWAITING_OTP;
import static com.banking.fundtransfer.entity.TransferStatus.BALANCE_CHECKING;
import static com.banking.fundtransfer.entity.TransferStatus.COMPLETED;
import static com.banking.fundtransfer.entity.TransferStatus.DEPOSITING;
import static com.banking.fundtransfer.entity.TransferStatus.EXPIRED;
import static com.banking.fundtransfer.entity.TransferStatus.FAILED;
import static com.banking.fundtransfer.entity.TransferStatus.FEE_PENDING;
import static com.banking.fundtransfer.entity.TransferStatus.OTP_PENDING;
import static com.banking.fundtransfer.entity.TransferStatus.RECONCILIATION_REQUIRED;
import static com.banking.fundtransfer.entity.TransferStatus.REVERSED;
import static com.banking.fundtransfer.entity.TransferStatus.REVERSING;
import static com.banking.fundtransfer.entity.TransferStatus.SUCCESS;
import static com.banking.fundtransfer.entity.TransferStatus.VALIDATING;
import static com.banking.fundtransfer.entity.TransferStatus.WITHDRAWING;

/** The allowed moves of a saga transaction, for each type. Anything not listed here is a bug and is refused. */
public final class TransferStateMachine {

    private static final Map<TransferType, Map<TransferStatus, Set<TransferStatus>>> MOVES =
            new EnumMap<>(TransferType.class);

    static {
        Map<TransferStatus, Set<TransferStatus>> transfer = new EnumMap<>(TransferStatus.class);
        allow(transfer, VALIDATING, FEE_PENDING, FAILED);
        allow(transfer, FEE_PENDING, BALANCE_CHECKING, FAILED);
        allow(transfer, BALANCE_CHECKING, OTP_PENDING, FAILED);
        allow(transfer, OTP_PENDING, AWAITING_OTP, FAILED);
        allow(transfer, AWAITING_OTP, WITHDRAWING, EXPIRED, FAILED);
        allow(transfer, WITHDRAWING, DEPOSITING, FAILED, RECONCILIATION_REQUIRED);
        allow(transfer, DEPOSITING, COMPLETED, REVERSING, RECONCILIATION_REQUIRED);
        allow(transfer, REVERSING, REVERSED, RECONCILIATION_REQUIRED);
        // A late answer from CBS can settle a transfer that was escalated after a timeout.
        allow(transfer, RECONCILIATION_REQUIRED, DEPOSITING, COMPLETED, REVERSING, REVERSED, FAILED);
        MOVES.put(TransferType.TRANSFER, transfer);

        // A cash deposit has one ledger step. The OTP step is only used when the setting asks for it.
        Map<TransferStatus, Set<TransferStatus>> deposit = new EnumMap<>(TransferStatus.class);
        allow(deposit, VALIDATING, OTP_PENDING, DEPOSITING, FAILED);
        allow(deposit, OTP_PENDING, AWAITING_OTP, FAILED);
        allow(deposit, AWAITING_OTP, DEPOSITING, EXPIRED, FAILED);
        allow(deposit, DEPOSITING, COMPLETED, FAILED, RECONCILIATION_REQUIRED);
        allow(deposit, RECONCILIATION_REQUIRED, COMPLETED, FAILED);
        MOVES.put(TransferType.DEPOSIT, deposit);

        // A cash withdrawal always needs the OTP, and has one ledger step.
        Map<TransferStatus, Set<TransferStatus>> withdrawal = new EnumMap<>(TransferStatus.class);
        allow(withdrawal, VALIDATING, BALANCE_CHECKING, FAILED);
        allow(withdrawal, BALANCE_CHECKING, OTP_PENDING, FAILED);
        allow(withdrawal, OTP_PENDING, AWAITING_OTP, FAILED);
        allow(withdrawal, AWAITING_OTP, WITHDRAWING, EXPIRED, FAILED);
        allow(withdrawal, WITHDRAWING, COMPLETED, FAILED, RECONCILIATION_REQUIRED);
        allow(withdrawal, RECONCILIATION_REQUIRED, COMPLETED, FAILED);
        MOVES.put(TransferType.WITHDRAWAL, withdrawal);
    }

    private TransferStateMachine() {}

    private static void allow(Map<TransferStatus, Set<TransferStatus>> map,
                              TransferStatus from, TransferStatus... to) {
        map.put(from, EnumSet.copyOf(Arrays.asList(to)));
    }

    public static boolean canMove(TransferType type, TransferStatus from, TransferStatus to) {
        return exits(type, from).contains(to);
    }

    /** Moves of a normal transfer (kept for the code that does not care about the type). */
    public static boolean canMove(TransferStatus from, TransferStatus to) {
        return canMove(TransferType.TRANSFER, from, to);
    }

    public static Set<TransferStatus> exits(TransferType type, TransferStatus from) {
        return MOVES.getOrDefault(type, Map.of()).getOrDefault(from, Set.of());
    }

    public static Set<TransferStatus> exits(TransferStatus from) {
        return exits(TransferType.TRANSFER, from);
    }

    public static boolean isSagaState(TransferStatus status) {
        return status != TransferStatus.PENDING_OTP
                && status != TransferStatus.PROCESSING
                && status != TransferStatus.UNCONFIRMED
                && status != SUCCESS;
    }

    /** Nothing can happen to a transaction in one of these states any more. */
    public static boolean isFinished(TransferStatus status) {
        return status == COMPLETED || status == FAILED || status == REVERSED
                || status == EXPIRED || status == SUCCESS;
    }
}