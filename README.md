# Fund Transfer Service

> The orchestrator of a money movement: it owns the `transfers` table, drives every transfer, deposit and withdrawal through a saga, and runs on port `8082` (management port `18082`).

## Table of Contents

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

`fund-transfer-service` is the **orchestrator** of a money movement. It owns the `transfers` table (one
row per money movement the user asked for) and drives every transfer, deposit and withdrawal through a
**saga**: it asks Account Service to validate the accounts, Charge Fee Service to price the transfer,
CBS to check the balance, the OTP Service to send a code, and finally CBS to withdraw from the sender
and deposit to the receiver — and, if the deposit fails, to reverse the withdrawal.

It **never writes to the ledger itself**; it only orchestrates. It has no balance of its own, no fee
rules of its own, and no idea what a one-time password is — it only asks for one and checks the answer.

The service supports **two flows**, selected by the setting `app.transfer.saga-enabled`:

- the *synchronous* (legacy) flow — statuses `PENDING_OTP` / `PROCESSING` / `UNCONFIRMED` / `SUCCESS`,
  all HTTP calls made in the request thread;
- the *saga* flow — statuses `VALIDATING` -> `FEE_PENDING` -> `BALANCE_CHECKING` -> `OTP_PENDING` ->
  `AWAITING_OTP` -> `WITHDRAWING` -> `DEPOSITING` -> `COMPLETED`, driven by Kafka events.

The flow of an existing transfer is decided by the row itself (`transfers.flow = "SAGA"`), so flipping
the setting never strands a running transfer.

It also owns the per-user limits (max pending transfers), the OTP lifecycle (issue, resend with a
cooldown, expiry) and the recovery of transfers that were in flight when the service stopped.

Sibling services it talks to:

| Service | Port | What it does for a transfer |
|---|---|---|
| `account-service` | 8081 | owns the accounts, the customer relationship and the account status |
| `charge-fee-service` | 8085 | prices the transfer (`sameOwner` -> `0.00`, otherwise `flatFee + amount * percentage / 100`, capped at `maxFee`) |
| `core-banking-service` | 8084 | the ledger: balance checks, withdrawals, deposits, reversals |
| `otp-service` | 8086 | issues and verifies the one-time password |
| `notification-service` | 8087 | sends the e-mail that carries the code |
| `api-gateway` | 8080 | the only public way in: routes `/api/transfers/**` to this service |

## Architecture At A Glance

```
  browser
     │  Authorization: Bearer <jwt>
     ▼
  api-gateway (8080)   ── strips X-User-Id / X-User-Email / X-User-Roles, sets them from the JWT
     │  Path=/api/transfers/**  ->  lb://fund-transfer-service
     ▼
  fund-transfer-service (8082)
     │
     │  saga: VALIDATING -> FEE_PENDING -> BALANCE_CHECKING -> OTP_PENDING
     │        -> AWAITING_OTP -> WITHDRAWING -> DEPOSITING -> COMPLETED
     ▼
  account.commands ─► account-service (8081)      account.events
  fee.commands     ─► charge-fee-service (8085)    fee.events
  cbs.commands     ─► core-banking-service (8084)   cbs.events
  otp.commands     ─► otp-service (8086)           otp.events
                                                 notification.events
     │
     ▼
  transfer.events ─► notification-service (8087) / audit
```

| Depends on | Depended on by |
|---|---|
| PostgreSQL `fund_transfer_db` | `api-gateway` (`api-gateway`, port 8080) — the only public caller |
| Kafka `account.commands`, `fee.commands`, `cbs.commands`, `otp.commands`, `transfer.events` | the web UI, through the gateway |
| Kafka `account.events`, `fee.events`, `otp.events`, `cbs.events`, `notification.events` | `core-banking-service` (8084) — answers the commands this service sends |
| Account Service (8081), Charge Fee Service (8085), Core Banking Service (8084), OTP Service (8086), Notification Service (8087) | |
| Eureka discovery (`discovery-service`, port 8761) | |

## HTTP API

**Controller file:** `fund-transfer-service/src/main/java/com/banking/fundtransfer/controller/TransferController.java`
**Class-level mapping:** `@RequestMapping("/api/transfers")`

### Auth and headers

- **`X-User-Id`** (Long) is required on every endpoint. **`X-User-Email`** (String) is required on the
  `POST` endpoints.
- These headers are **set by the API gateway** from the validated JWT and are stripped from any
  client-sent copy. The gateway route is `Path=/api/transfers/**` -> `lb://fund-transfer-service`.
- The service itself does **not** validate the JWT; it trusts the gateway. If a header is missing,
  `GlobalExceptionHandler.handleMissingHeader` answers **401 Unauthorized** with the message
  `"Missing user identity"`.
- Ownership of an account is always checked against Account Service, never taken from the client.

### Endpoint list

| # | Method | Full path | Required headers | Request body | Success status | Response body |
|---|---|---|---|---|---|---|
| 1 | `POST` | `/api/transfers` | `X-User-Id`, `X-User-Email` | `InitiateTransferRequest` | **202 Accepted** (saga) or **200 OK** (sync) | `TransferStatusResponse` (saga) or `InitiateTransferResponse` (sync) |
| 2 | `POST` | `/api/transfers/deposit` | `X-User-Id`, `X-User-Email` | `CashTransactionRequest` | **202 Accepted** (saga) or **200 OK** (sync) | `TransferStatusResponse` (saga) or `InitiateTransferResponse` (sync) |
| 3 | `POST` | `/api/transfers/withdraw` | `X-User-Id`, `X-User-Email` | `CashTransactionRequest` | **202 Accepted** (saga) or **200 OK** (sync) | `TransferStatusResponse` (saga) or `InitiateTransferResponse` (sync) |
| 4 | `POST` | `/api/transfers/{transferId}/confirm` | `X-User-Id`, `X-User-Email` | `ConfirmTransferRequest` | **200 OK** if finished, **202 Accepted** if still running | `TransferStatusResponse` (saga) or `ConfirmTransferResponse` (sync) |
| 5 | `POST` | `/api/transfers/{transferId}/resend-otp` | `X-User-Id` | – | **202 Accepted** | `TransferStatusResponse` |
| 6 | `GET` | `/api/transfers/{transferId}` | `X-User-Id` | – | 200 OK | `TransferStatusResponse` |
| 7 | `GET` | `/api/transfers` | `X-User-Id` | – | 200 OK | `List<TransferResponse>` |

### POST /api/transfers — initiate a transfer

| Field | Type | Constraints (exact messages) |
|---|---|---|
| `fromAccountNumber` | `String` | `@NotBlank(message = "fromAccountNumber is required")`, `@Size(max = 50, message = "fromAccountNumber must be at most 50 characters")` |
| `toAccountNumber` | `String` | `@NotBlank(message = "toAccountNumber is required")`, `@Size(max = 50, message = "toAccountNumber must be at most 50 characters")` |
| `amount` | `BigDecimal` | `@NotNull(message = "amount is required")`, `@DecimalMin(value = "0.01", message = "amount must be at least 0.01")`, `@Digits(integer = 15, fraction = 2, message = "amount allows at most 2 decimal places")` |

Saga response `TransferStatusResponse` = `record TransferStatusResponse(String transferId, String type, String status, boolean finished, String message, String reasonCode, String fromAccountNumber, String toAccountNumber, BigDecimal amount, BigDecimal fee, BigDecimal totalDebit, LocalDateTime otpExpiresAt, LocalDateTime createdAt, LocalDateTime updatedAt)`.

Sync response `InitiateTransferResponse` = `record InitiateTransferResponse(String transferId, BigDecimal fee, BigDecimal totalDebit, String status, String message)`.

`fromAccountNumber == toAccountNumber` -> `InvalidTransferException("Cannot transfer to the same account")` (**400**).

### POST /api/transfers/deposit and POST /api/transfers/withdraw

Body `CashTransactionRequest` (identical for both):

| Field | Type | Constraints (exact messages) |
|---|---|---|
| `accountNumber` | `String` | `@NotBlank(message = "accountNumber is required")`, `@Size(max = 50, message = "accountNumber must be at most 50 characters")` |
| `amount` | `BigDecimal` | `@NotNull(message = "amount is required")`, `@DecimalMin(value = "0.01", message = "amount must be at least 0.01")`, `@DecimalMax(value = "1000000.00", message = "amount must be at most 1,000,000.00")`, `@Digits(integer = 15, fraction = 2, message = "amount allows at most 2 decimal places")` |

In the saga flow a deposit moves to `from = "CASH"`, `to = <accountNumber>`; a withdrawal to
`from = <accountNumber>`, `to = "CASH"`.

### POST /api/transfers/{transferId}/confirm

Body `ConfirmTransferRequest` = `record ConfirmTransferRequest(@Pattern(regexp = "\\d{6}", message = "otp must be exactly 6 digits") String otp)`.

The `otp` field is optional in the schema but must be exactly 6 digits when present.
The response is `TransferStatusResponse` (saga) or `ConfirmTransferResponse`
(`record ConfirmTransferResponse(String transferId, String status, String message)`) for the synchronous flow.

The controller decides the flow **from the transfer row**, not from the setting:
`if (sagaService.isSagaTransfer(userId, transferId))` — source comment: *"The transaction itself decides
the flow, so a switch of the setting never strands a running one."*

In the saga flow the answer depends on the current state:

| Current status | Result |
|---|---|
| `VALIDATING`, `FEE_PENDING`, `BALANCE_CHECKING` | **409 Conflict** `"Your request is still being checked, please wait a moment"` |
| `OTP_PENDING` | **409 Conflict** `"The one-time password is still being sent, please wait a moment"` |
| `AWAITING_OTP` | the OTP is verified synchronously; **200 OK** when the transfer is finished, **202 Accepted** otherwise |
| anything else | the current state is reported (**200 OK** if finished, **202 Accepted** if not) |

A missing OTP -> `OtpRejectedException("OTP is required")` (**400**). A wrong OTP -> the message of
the OTP service (**400**).

### POST /api/transfers/{transferId}/resend-otp

No body. Always **202 Accepted** with `TransferStatusResponse`. Only saga transfers support it.

- Not a saga transfer -> `InvalidTransferException("This transfer does not support a new code")` (**400**).
- Not in `AWAITING_OTP` -> `InvalidTransferException("A new code can only be requested while the transfer is waiting for the OTP")` (**400**).
- Inside the cooldown -> `ResendTooSoonException("Please wait " + wait + " seconds before requesting a new code")` (**429**).

### GET /api/transfers/{transferId}

**200 OK**, `TransferStatusResponse`. A transfer that does not belong to the caller is answered with
**404** `"Transfer not found"`.

### GET /api/transfers

**200 OK**, `List<TransferResponse>` where
`TransferResponse` = `record TransferResponse(String transferId, String type, String fromAccountNumber, String toAccountNumber, BigDecimal amount, BigDecimal fee, String status, LocalDateTime createdAt)`.
Returns the **100 most recent** transfers of the user (`findTop100ByUserIdOrderByCreatedAtDesc`).

### Error Response Shape

Body = `ErrorResponse` (`timestamp`, `status`, `error`, `message`) with `error` = `HttpStatus.getReasonPhrase()`.

| Field | Type |
|---|---|
| `timestamp` | `LocalDateTime` |
| `status` | `int` |
| `error` | `String` (the HTTP reason phrase) |
| `message` | `String` |

```json
{
  "timestamp": "2026-11-02T10:15:31.204",
  "status": 409,
  "error": "Conflict",
  "message": "Your request is still being checked, please wait a moment"
}
```

| Exception | HTTP status | Message |
|---|---|---|
| `AccountNotFoundException` | **404** Not Found | `"Account not found: X"` |
| `TransferNotFoundException` | **404** Not Found | `"Transfer not found"` |
| `NotAccountOwnerException` | **403** Forbidden | `"You do not own the source account"` / `"You do not own this account"` |
| `InvalidTransferException` | **400** Bad Request | e.g. `"Cannot transfer to the same account"`, `"Account is not active"`, `"Currency mismatch between accounts"`, `"A new code can only be requested while the transfer is waiting for the OTP"`, `"This transfer cannot be confirmed in its current state"`, `"Transfer is in an invalid state, please start a new one"`, `"This transfer does not support a new code"` |
| `OtpRejectedException` | **400** Bad Request | `"OTP is required"`, `"No OTP was issued for this transfer. Please start a new transfer"`, or the OTP service message |
| `TransferExpiredException` | **400** Bad Request | `"Transfer expired, please start a new one"` |
| `TransferInProgressException` | **409** Conflict | `"Transfer is already being processed"`, `"Your request is still being checked, please wait a moment"`, `"The one-time password is still being sent, please wait a moment"` |
| `InsufficientBalanceException` | **422** Unprocessable Entity | `"Insufficient balance"` |
| `TooManyPendingTransfersException` | **429** Too Many Requests | `"You have too many unconfirmed transfers. Confirm or wait for them to expire"` |
| `ResendTooSoonException` | **429** Too Many Requests | `"Please wait N seconds before requesting a new code"` |
| `DownstreamUnavailableException` | **503** Service Unavailable | `"Account service is unavailable, please try again"`, `"Fee service is unavailable, please try again"`, `"Could not send the OTP, please try again"`, `"OTP service is unavailable, please try again"`, `"Banking core is unavailable, please try again"`, `"Banking core returned an invalid response"`, `"Fee service returned an invalid response"` |
| `ResultUnconfirmedException` | **503** Service Unavailable | `"We could not confirm the result yet. Press Confirm again in a moment, or check History. Do not start a new transfer."` |
| `MissingRequestHeaderException` | **401** Unauthorized | `"Missing user identity"` |
| `MethodArgumentTypeMismatchException` | **400** Bad Request | `"Invalid value for <name>"` |
| `MethodArgumentNotValidException` | **400** Bad Request | `"<field>: <message>"` joined by `"; "` |
| `HttpMessageNotReadableException` | **400** Bad Request | `"Malformed request body"` |
| `NoResourceFoundException` | **404** Not Found | `"Not found"` |
| any other `Exception` | **500** Internal Server Error | `"Something went wrong"` |

## Database

Database: **`fund_transfer_db`** (`${DB_URL:jdbc:postgresql://localhost:5433/fund_transfer_db}`).
User `${DB_USERNAME:banking_admin}`, password `${DB_PASSWORD:postgre2188}`.
`spring.jpa.hibernate.ddl-auto: update`, `spring.jpa.open-in-view: false`.

### `transfers` — entity `com.banking.fundtransfer.entity.Transfer`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `transfer_id` | `character varying(50)` | not null | **PRIMARY KEY** |
| `amount` | `numeric(19,2)` | not null | |
| `cbs_transaction_id` | `character varying(64)` | nullable | the CBS `transactionId` |
| `created_at` | `timestamp(6) without time zone` | not null | `@PrePersist`, `updatable = false` |
| `failure_reason` | `character varying(255)` | nullable | truncated to 255 |
| `fee` | `numeric(19,2)` | not null | |
| `from_account_number` | `character varying(50)` | not null | |
| `otp_id` | `character varying(64)` | nullable | |
| `otp_verified` | `boolean` | not null | |
| `status` | `character varying(30)` | not null | **`transfers_status_check`**, see below |
| `to_account_number` | `character varying(50)` | not null | |
| `updated_at` | `timestamp(6) without time zone` | not null | `@PreUpdate` |
| `user_id` | `bigint` | not null | |
| `type` | `character varying(20)` | not null | **`transfers_type_check`** `IN ('TRANSFER','DEPOSIT','WITHDRAWAL')` |
| `currency` | `character varying(3)` | nullable | set from the validation event |
| `failure_code` | `character varying(50)` | nullable | truncated to 50 |
| `flow` | `character varying(10)` | nullable | `"SAGA"` for saga transfers, null for the synchronous flow |
| `last_otp_requested_at` | `timestamp(6) without time zone` | nullable | used for the resend cooldown |
| `otp_expires_at` | `timestamp(6) without time zone` | nullable | |
| `same_owner` | `boolean` | nullable | set from `TransferValidated` |
| `state_entered_at` | `timestamp(6) without time zone` | nullable | when the current state was entered (timeout clock) |
| `step_attempts` | `integer` | nullable | resend counter of the current step |
| `user_email` | `character varying(150)` | nullable | |

Index `idx_transfers_user_created (user_id, created_at)`.

### `transfers_status_check` — the allowed status values

The constraint is defined in `postgres/init/03-fund-transfer-schema.sql` **and** repeated in
`postgres/init/04-repair-schema.sql` (the repair script runs on every start of `run-all.sh`):

```sql
ALTER TABLE transfers DROP CONSTRAINT IF EXISTS transfers_status_check;
ALTER TABLE transfers ADD CONSTRAINT transfers_status_check CHECK (status IN (
    'PENDING_OTP', 'PROCESSING', 'UNCONFIRMED', 'SUCCESS',
    'FAILED', 'EXPIRED',
    'VALIDATING', 'FEE_PENDING', 'BALANCE_CHECKING', 'OTP_PENDING', 'AWAITING_OTP',
    'WITHDRAWING', 'DEPOSITING', 'COMPLETED',
    'REVERSING', 'REVERSED', 'RECONCILIATION_REQUIRED'
));
```

That is **17 values**, in this order:
`PENDING_OTP`, `PROCESSING`, `UNCONFIRMED`, `SUCCESS`, `FAILED`, `EXPIRED`, `VALIDATING`, `FEE_PENDING`,
`BALANCE_CHECKING`, `OTP_PENDING`, `AWAITING_OTP`, `WITHDRAWING`, `DEPOSITING`, `COMPLETED`, `REVERSING`,
`REVERSED`, `RECONCILIATION_REQUIRED`.

Background from `03-fund-transfer-schema.sql` (verbatim): *"The status check constraint also only allowed
the statuses of the original synchronous flow. Every saga status was rejected by it, so a saga transfer
failed with 'new row for relation transfers violates check constraint transfers_status_check'."*

The Java enum `com.banking.fundtransfer.entity.TransferStatus` contains exactly the same 17 constants:

```java
public enum TransferStatus {
    // the original synchronous flow
    PENDING_OTP, PROCESSING, UNCONFIRMED, SUCCESS,
    // both flows
    FAILED, EXPIRED,
    // the saga flow
    VALIDATING, FEE_PENDING, BALANCE_CHECKING, OTP_PENDING, AWAITING_OTP,
    WITHDRAWING, DEPOSITING, COMPLETED, REVERSING, REVERSED, RECONCILIATION_REQUIRED
}
```

`com.banking.fundtransfer.entity.TransferType` = `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`.

### `processed_events` — entity `com.banking.fundtransfer.messaging.ProcessedEvent`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | `character varying(160)` | not null | **PRIMARY KEY** — the value is `"<handler>:<eventId>"` |
| `processed_at` | `timestamp(6) with time zone` | not null | |

Inserted with `insert into processed_events (id, processed_at) values (:id, now()) on conflict (id) do nothing`.
`04-repair-schema.sql` renames an old `event_id` column to `id` and casts it to `varchar(160)`.

### `outbox_events` — entity `com.banking.fundtransfer.messaging.OutboxEvent`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | `uuid` | not null | **PRIMARY KEY** (= the `eventId`) |
| `attempts` | `integer` | not null | |
| `created_at` | `timestamp with time zone` | not null | |
| `last_error` | `character varying(500)` | nullable | |
| `message_key` | `character varying(100)` | not null | |
| `envelope` | `text` | not null | the serialized `EventEnvelope` |
| `published_at` | `timestamp with time zone` | nullable | |
| `topic` | `character varying(100)` | not null | |
| `event_type` | `character varying(100)` | not null | **DEFAULT `'UNKNOWN'`** (added by the repair script) |

Index `idx_outbox_pending (published_at, created_at)`.
`04-repair-schema.sql` also renames an old `payload` column to `envelope`, casts a legacy `varchar` id to
`uuid`, and converts `created_at` / `published_at` to `timestamptz`.

Note: `com.banking.fundtransfer.repository.OutboxEventRepository` is a **second, legacy** repository
(`JpaRepository<OutboxEvent, String>`) that does not match the entity's `UUID` id and is only used by
`TransferOutcomeRecorder`. The **active** outbox path is `com.banking.fundtransfer.messaging.OutboxRepository`
(`JpaRepository<OutboxEvent, UUID>`), used by `OutboxEventPublisher` and `OutboxRelay`.

## Kafka Topics

`com.banking.fundtransfer.messaging.TopicNames`:

```java
public static final String ACCOUNT_COMMANDS    = "account.commands";
public static final String ACCOUNT_EVENTS      = "account.events";
public static final String FEE_COMMANDS        = "fee.commands";
public static final String FEE_EVENTS          = "fee.events";
public static final String OTP_COMMANDS        = "otp.commands";
public static final String OTP_EVENTS          = "otp.events";
public static final String CBS_COMMANDS        = "cbs.commands";
public static final String CBS_EVENTS          = "cbs.events";
public static final String TRANSFER_EVENTS     = "transfer.events";
public static final String NOTIFICATION_EVENTS = "notification.events";
public static final String DLT_SUFFIX          = ".DLT";
```

Every topic has 3 partitions, replication factor 1, and a dead-letter twin `<topic>.DLT`.
Note that `otp.events` has a much shorter retention (2 hours) than the others (7 days).

### Published — commands this service sends

| Topic | Event type | Payload record | Fields |
|---|---|---|---|
| `account.commands` | `ValidateTransfer` | `ValidateTransferPayload` | `transferId`, `userId` (Long), `fromAccountNumber`, `toAccountNumber`, `amount` (BigDecimal, `@JsonFormat(STRING)`) |
| `account.commands` | `ValidateCashAccount` | `ValidateCashAccountPayload` | `transferId`, `userId` (Long), `accountNumber`, `operation` (String — `"DEPOSIT"` or `"WITHDRAWAL"`), `amount` (BigDecimal, `@JsonFormat(STRING)`) |
| `fee.commands` | `CalculateFee` | `CalculateFeePayload` | `transferId`, `fromAccountNumber`, `toAccountNumber`, `amount` (BigDecimal, `@JsonFormat(STRING)`), `sameOwner` (boolean) |
| `cbs.commands` | `CheckBalance` | `CheckBalancePayload` | `transferId`, `commandId`, `accountNumber`, `amount` (BigDecimal), `fee` (BigDecimal) |
| `cbs.commands` | `Withdraw` | `WithdrawPayload` | `transferId`, `commandId`, `accountNumber`, `amount` (BigDecimal, STRING), `fee` (BigDecimal, STRING), `destination` (String — `"TRANSIT"` for a transfer, `"CASH"` for a cash withdrawal) |
| `cbs.commands` | `Deposit` | `DepositPayload` | `transferId`, `commandId`, `accountNumber`, `amount` (BigDecimal, STRING), `source` (String — `"TRANSIT"` for a transfer, `"CASH"` for a cash deposit) |
| `cbs.commands` | `Reversal` | `ReversalPayload` | `transferId`, `commandId`, `accountNumber`, `amount` (BigDecimal, STRING), `fee` (BigDecimal, STRING) |
| `otp.commands` | `IssueOtp` | `IssueOtpPayload` | `transferId`, `userId` (Long), `email` (String), `purpose` (String — always `"TRANSFER"`) |
| `transfer.events` | `TransferCompleted` / `TransferFailed` / `TransferReversed` | `TransferOutcomePayload` | `transferId`, `type` (String), `userId` (Long), `email` (String), `fromAccountNumber`, `toAccountNumber`, `amount` (BigDecimal, STRING), `fee` (BigDecimal, STRING), `reason` (String, nullable) |
| `transfer.events` | `ReconciliationRequired` | `ReconciliationRequiredPayload` | `transferId`, `state` (String — the status the transfer was in), `details` (String) |

The **command id** pattern for the CBS commands is fixed:

- `CheckBalance` -> `"<transferId>:CHECK_BALANCE"`
- `Withdraw` -> `"<transferId>:WITHDRAW"`
- `Deposit` -> `"<transferId>:DEPOSIT"`
- `Reversal` -> `"<transferId>:REVERSAL"`

Kafka message key = the `transferId` for every published message.
`correlationId` = the `transferId`; `causationId` = the `eventId` of the event that caused the command
(or `null` for the first command and for user-triggered actions).

### Consumed

`com.banking.fundtransfer.messaging.consumer.SagaEventsConsumer`:

```java
@KafkaListener(topics = {
        TopicNames.ACCOUNT_EVENTS,
        TopicNames.FEE_EVENTS,
        TopicNames.OTP_EVENTS,
        TopicNames.NOTIFICATION_EVENTS,
        TopicNames.CBS_EVENTS})
```

| `eventType` | Payload record | Fields | Handler |
|---|---|---|---|
| `TransferValidated` | `TransferValidatedEvent` | `transferId`, `currency`, `sameOwner` (boolean) | `onTransferValidated` |
| `TransferRejected` | `FailureEvent` | `transferId`, `reasonCode`, `reason` | `onTransferRejected` |
| `CashAccountValidated` | `CashAccountValidatedEvent` | `transferId`, `currency` | `onCashAccountValidated` |
| `CashAccountRejected` | `FailureEvent` | `transferId`, `reasonCode`, `reason` | `onCashAccountRejected` |
| `FeeCalculated` | `FeeCalculatedEvent` | `transferId`, `fee` (BigDecimal), `feeType` (String) | `onFeeCalculated` |
| `BalanceChecked` | `BalanceCheckedEvent` | `transferId`, `accountNumber`, `availableBalance` (BigDecimal), `requiredAmount` (BigDecimal) | `onBalanceChecked` |
| `BalanceRejected` | `FailureEvent` | `transferId`, `reasonCode`, `reason` | `onBalanceRejected` |
| `OtpIssued` | `OtpIssuedEvent` | `transferId`, `otpId` (String), `expiresAt` (Instant) | `onOtpIssued` |
| `OtpDeliveryFailed` | `OtpDeliveryFailedEvent` | `transferId`, `reason` (String) | `onOtpDeliveryFailed` |
| `WithdrawCompleted` | `WithdrawCompletedEvent` | `transferId`, `accountNumber`, `amount` (BigDecimal), `fee` (BigDecimal) | `onWithdrawCompleted` |
| `WithdrawFailed` | `FailureEvent` | `transferId`, `reasonCode`, `reason` | `onWithdrawFailed` |
| `DepositCompleted` | `DepositCompletedEvent` | `transferId`, `accountNumber`, `amount` (BigDecimal) | `onDepositCompleted` |
| `DepositFailed` | `FailureEvent` | `transferId`, `reasonCode`, `reason` | `onDepositFailed` |
| `ReversalCompleted` | `ReversalCompletedEvent` | `transferId`, `accountNumber`, `refundedAmount` (BigDecimal) | `onReversalCompleted` |
| `ReversalFailed` | `FailureEvent` | `transferId`, `reasonCode`, `reason` | `onReversalFailed` |
| `OtpIssueRejected` | – | – | only counted: *"A resend came too early. Nothing changes: the old code stays valid."* |

Unknown types are ignored with a debug log.
The handler name used for the idempotency key is the constant `HANDLER = "saga-events"`.

### The full saga state machine

`com.banking.fundtransfer.saga.TransferStateMachine` — *"The allowed moves of a saga transaction, for each
type. Anything not listed here is a bug and is refused."* An illegal move throws
`IllegalStateException("Illegal " + type + " move " + from + " -> " + to)`.

#### `TransferType.TRANSFER`

| From | To |
|---|---|
| `VALIDATING` | `FEE_PENDING`, `FAILED` |
| `FEE_PENDING` | `BALANCE_CHECKING`, `FAILED` |
| `BALANCE_CHECKING` | `OTP_PENDING`, `FAILED` |
| `OTP_PENDING` | `AWAITING_OTP`, `FAILED` |
| `AWAITING_OTP` | `WITHDRAWING`, `EXPIRED`, `FAILED` |
| `WITHDRAWING` | `DEPOSITING`, `FAILED`, `RECONCILIATION_REQUIRED` |
| `DEPOSITING` | `COMPLETED`, `REVERSING`, `RECONCILIATION_REQUIRED` |
| `REVERSING` | `REVERSED`, `RECONCILIATION_REQUIRED` |
| `RECONCILIATION_REQUIRED` | `DEPOSITING`, `COMPLETED`, `REVERSING`, `REVERSED`, `FAILED` |

Source comment on the last row: *"A late answer from CBS can settle a transfer that was escalated after a timeout."*

#### `TransferType.DEPOSIT`

| From | To |
|---|---|
| `VALIDATING` | `OTP_PENDING`, `DEPOSITING`, `FAILED` |
| `OTP_PENDING` | `AWAITING_OTP`, `FAILED` |
| `AWAITING_OTP` | `DEPOSITING`, `EXPIRED`, `FAILED` |
| `DEPOSITING` | `COMPLETED`, `FAILED`, `RECONCILIATION_REQUIRED` |
| `RECONCILIATION_REQUIRED` | `COMPLETED`, `FAILED` |

Source comment: *"A cash deposit has one ledger step. The OTP step is only used when the setting asks for it."*

#### `TransferType.WITHDRAWAL`

| From | To |
|---|---|
| `VALIDATING` | `BALANCE_CHECKING`, `FAILED` |
| `BALANCE_CHECKING` | `OTP_PENDING`, `FAILED` |
| `OTP_PENDING` | `AWAITING_OTP`, `FAILED` |
| `AWAITING_OTP` | `WITHDRAWING`, `EXPIRED`, `FAILED` |
| `WITHDRAWING` | `COMPLETED`, `FAILED`, `RECONCILIATION_REQUIRED` |
| `RECONCILIATION_REQUIRED` | `COMPLETED`, `FAILED` |

Source comment: *"A cash withdrawal always needs the OTP, and has one ledger step."*

Helpers:

- `isSagaState(status)` -> true for everything except `PENDING_OTP`, `PROCESSING`, `UNCONFIRMED`, `SUCCESS`.
- `isFinished(status)` -> true for `COMPLETED`, `FAILED`, `REVERSED`, `EXPIRED`, `SUCCESS`.

### The saga flow, step by step (source: `TransferSagaEngine`)

1. **`VALIDATING`** — the transfer row is created with `flow = "SAGA"`, `transferId = "TXN-" + <16 hex
   chars uppercase>`, `fee = 0.00`, `otpVerified = false`, and the state moves to `VALIDATING`.
   A `ValidateTransfer` (TRANSFER) or `ValidateCashAccount` (DEPOSIT/WITHDRAWAL) command is published.
2. **`FEE_PENDING`** — on `TransferValidated` (TRANSFER only): `sameOwner` and `currency` are stored and a
   `CalculateFee` command is published. On `TransferRejected`: the transfer **fails** with the reason
   code from the event.
3. **`BALANCE_CHECKING`** — on `FeeCalculated`: the fee is validated (null, negative or more than 2
   decimals -> `fail("INVALID_FEE", "The fee service returned an invalid fee")`), stored, and a
   `CheckBalance` command is published. Source comment: *"The total debit is only known now that the fee is
   known, so this is the first moment the balance can be checked against it."*
   For a **cash withdrawal** the state also goes to `BALANCE_CHECKING` (fee = 0, so required = amount).
   For a **cash deposit** there is no balance check: *"A deposit adds money, so there is no balance to check."*
4. **`OTP_PENDING`** — on `BalanceChecked`: `lastOtpRequestedAt = now()`, move to `OTP_PENDING`, publish
   `IssueOtp`. For a cash deposit with `app.transfer.deposit-requires-otp = false`, the state jumps
   straight to `DEPOSITING` and `otpVerified = true` (*"A deposit needs no OTP: the request itself is the
   authorization."*).
   On `BalanceRejected`: the transfer **fails** with the reason code from CBS
   (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND`, `ACCOUNT_INACTIVE`). Source comment: *"Nothing was moved,
   and no code was sent, so this is only shown on the screen."*
5. **`AWAITING_OTP`** — on `OtpIssued`: `otpId` and `otpExpiresAt` are stored. A new code (first one or a
   resend) replaces the previous `otpId` (*"The OTP service invalidated the old code."*).
   On `OtpDeliveryFailed`: `fail("OTP_DELIVERY_FAILED", "The one-time password could not be delivered.
   Please start again")`.
6. **`WITHDRAWING`** — the user confirms with the right OTP. `TransferSagaService.verifyAndStart` checks
   the OTP **synchronously** (source comment: *"Checking the code stays synchronous: the user waits for
   the answer."*), then `TransferSagaEngine.otpVerified` sets `otpVerified = true` and publishes `Withdraw`
   (`destination = "TRANSIT"` for a transfer, `"CASH"` for a cash withdrawal).
7. **`DEPOSITING`** — on `WithdrawCompleted`: the amounts CBS reports are compared with the request; a
   difference escalates to `RECONCILIATION_REQUIRED` with `AMOUNT_MISMATCH`. Otherwise a `Deposit` is
   published (`source = "TRANSIT"` for a transfer, `"CASH"` for a cash deposit).
   A **cash withdrawal** finishes here: `COMPLETED` + `TransferCompleted`.
8. **`COMPLETED`** — on `DepositCompleted`: the amount is checked (a difference -> `AMOUNT_MISMATCH`
   escalation), the transfer becomes `COMPLETED` and a `TransferCompleted` event is published.
9. **`REVERSING` / `REVERSED`** — on `DepositFailed` for a **transfer**: the sender gets everything back
   including the fee, so the transfer moves to `REVERSING` and a `Reversal` command is published. For a
   **cash deposit** a failure simply fails the transfer (*"A cash deposit that CBS refused changed nothing
   in the ledger, so there is nothing to reverse."*).
   On `ReversalCompleted` the transfer becomes `REVERSED` and a `TransferReversed` event is published; if
   the failure code was `STEP_TIMEOUT` the reason becomes *"The transfer took too long and was reversed"*.
   On `ReversalFailed` the transfer escalates: `escalate("REVERSAL_FAILED", "The refund was refused by CBS: " + reasonCode)`
   — *"This can only be solved by a person."*
10. **`RECONCILIATION_REQUIRED`** — set by `escalate(...)`, which also publishes `ReconciliationRequired`
    on `transfer.events` and increments `transfers.reconciliation.escalations`.

#### The CheckBalance early check

The balance check is a distinct step of its own, and it is deliberately placed **after the fee is known
and before the OTP is requested**. The source comment in `TransferSagaEngine.publishCheckBalance` says:

> "Asks the banking core whether the account can cover the debit. The fee of a cash transaction is zero, so
> the required amount is the amount itself. The check moves no money, so CBS answers every resend again
> with the balance of that moment instead of keeping the first answer."

And in `onCashAccountValidated`:

> "The balance is checked before the code is asked for, so the user is never sent a code for a transaction
> that cannot succeed."

Consequences for the user:

- A transfer whose sender cannot cover `amount + fee` fails with the CBS reason code
  (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND` or `ACCOUNT_INACTIVE`) **before** any e-mail is sent.
- A cash deposit never gets a balance check (a deposit adds money).
- The authoritative check still happens when the money is posted, because the balance can change between
  the check and the posting.

### Idempotency of the consumer — `ProcessedEventGuard`

```java
@Transactional(propagation = Propagation.MANDATORY)
public boolean firstTime(String handler, String eventId) {
    return repository.insertIfAbsent(handler + ":" + eventId) == 1;
}
```

It **joins the caller's transaction** (`Propagation.MANDATORY`). The key is
`"saga-events:" + eventId` (the handler name is the constant `HANDLER = "saga-events"`).
`TransferSagaEngine.open(...)` calls it first; a repeat returns `Optional.empty()` and the event is
counted as `duplicate`.

`ProcessedEventCleaner` deletes the rows older than **14 days**
(`@Scheduled(cron = "0 30 3 * * *")`) — *"Topics keep messages for 7 days, so ids older than 14 days can
no longer be redelivered."*

### Outbox and retries

- `OutboxEventPublisher.publish(...)` is `@Transactional(propagation = Propagation.MANDATORY)`: the event
  must be written inside the transaction that changes the data.
- `OutboxRelay.publishPending()` — `@Scheduled(fixedDelayString = "${app.messaging.relay-interval-ms:1000}")`,
  oldest **50** unpublished rows, `kafka.send(...).get(30, TimeUnit.SECONDS)`.
  On a failure the error is truncated to 500 chars, `attempts` is incremented, and the loop returns
  immediately so a later event never overtakes the failed one.
- `deleteOldPublishedEvents()` — `@Scheduled(cron = "0 15 3 * * *")`, deletes published events older than **7 days**.
- `KafkaConsumerConfig`: `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries(4)`,
  initial 1000 ms, multiplier 2.0, max 10000 ms; `InvalidEventException` is not retryable; DLT = `<topic>.DLT`.
- Producer: `acks: all`, `enable.idempotence: true`, `linger.ms: 0`, `max.block.ms: 15000`,
  `request.timeout.ms: 5000`, `delivery.timeout.ms: 10000`.

### Metrics

- `transfers.transitions` counter, tags `from` / `to` (the initial one uses `from = "NONE"`).
- `transfers.resends` counter, tag `state`.
- `transfers.reconciliation.escalations` counter, tag `code`.
- `saga.events` counter, tags `type` (the event type) / `result` (`completed|failed|duplicate|ignored|unknown|noted`).
- `transfers.duration` timer, tag `outcome` (`completed|failed|reversed|expired`).
- `transfers.in.flight` gauge, tag `status` — one per saga state.
- `outbox.events.published`, `outbox.publish.failures`, `outbox.pending.events`.

## Configuration

`fund-transfer-service/src/main/resources/application.yaml` — the full `app.*` set:

| Property | Default | Meaning |
|---|---|---|
| `app.services.account` | `${ACCOUNT_SERVICE_URL:http://account-service}` | base URL of Account Service (Eureka service name) |
| `app.services.charge-fee` | `${CHARGE_FEE_SERVICE_URL:http://charge-fee-service}` | base URL of Charge Fee Service |
| `app.services.otp` | `${OTP_SERVICE_URL:http://otp-service}` | base URL of OTP Service |
| `app.services.cbs` | `${CBS_SERVICE_URL:http://core-banking-service}` | base URL of Core Banking Service |
| `app.services.notification` | `${NOTIFICATION_SERVICE_URL:http://notification-service}` | base URL of Notification Service |
| `app.transfer.pending-expiry-minutes` | `10` | how long a `PENDING_OTP` transfer (and a saga transfer before payment) may stay open |
| `app.transfer.max-pending-per-user` | `5` | the maximum number of unconfirmed transfers per user |
| `app.transfer.notify-email` | `${TRANSFER_NOTIFY_EMAIL:USER}` | `USER` = send to the address of the user who started the transaction; **any other value sends every OTP to that one address** (demo setting — the real user then never receives the OTP) |
| `app.transfer.deposit-requires-otp` | `false` | whether a cash deposit needs the one-time password |
| `app.transfer.saga-enabled` | `${TRANSFER_SAGA_ENABLED:false}` | `true` = the saga flow (HTTP 202 + polling); `false` = the synchronous flow |
| `app.saga.step-timeout-seconds` | `30` | how long a step may wait for an answer before it is retried |
| `app.saga.max-resends` | `3` | how many times a step's command is re-sent before the transfer fails |
| `app.saga.reversal-max-resends` | `5` | the same for the reversal step |
| `app.saga.otp-issue-timeout-seconds` | `60` | how long `OTP_PENDING` may wait for the code |
| `app.saga.otp-resend-cooldown-seconds` | `30` | the minimum time between two OTP requests |
| `app.saga.sweep-interval-ms` | `5000` | how often the timeout sweeper runs |
| `app.messaging.producer-name` | `fund-transfer-service` | the `producer` field of every published envelope |
| `app.messaging.relay-interval-ms` | `1000` | outbox relay poll interval |
| `app.outbox.poll-interval-ms` | `1000` | **only referenced by the commented-out `OutboxRelayJob`** — not active |
| `app.outbox.retention-days` | `7` | **only referenced by the commented-out `OutboxRelayJob`** — the active relay hard-codes 7 days |

Bound by `@ConfigurationProperties` records:

- `com.banking.fundtransfer.config.ServicesProperties` — prefix `app.services`: `account`, `chargeFee`, `otp`, `cbs`, `notification`.
- `com.banking.fundtransfer.config.TransferProperties` — prefix `app.transfer`: `pendingExpiryMinutes`, `maxPendingPerUser`, `notifyEmail`, `depositRequiresOtp`, `sagaEnabled`.
- `com.banking.fundtransfer.config.SagaProperties` — prefix `app.saga`: `stepTimeoutSeconds`, `maxResends`, `reversalMaxResends`, `otpIssueTimeoutSeconds`, `otpResendCooldownSeconds`. **Note:** `sweep-interval-ms` is **not** a field of this record; it is read directly from the placeholder in `SagaTimeoutSweeper` (`${app.saga.sweep-interval-ms:5000}`).
- `com.banking.fundtransfer.messaging.MessagingProperties` — prefix `app.messaging`: `producerName`.

Other settings:

| Property | Value / default |
|---|---|
| `server.port` | `8082` |
| `management.server.port` | `18082` |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` |
| `spring.datasource.url` | `${DB_URL:jdbc:postgresql://localhost:5433/fund_transfer_db}` |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` |
| `spring.jpa.hibernate.ddl-auto` | `update` |
| `spring.jpa.open-in-view` | `false` |
| `spring.kafka.*` | identical to CBS (`bootstrap-servers ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, group id `fund-transfer-service`, `auto-offset-reset: earliest`, `enable-auto-commit: false`, `ack-mode: record`, `concurrency: 3`, `acks: all`, idempotence on, `linger.ms: 0`, `max.block.ms: 15000`, `request.timeout.ms: 5000`, `delivery.timeout.ms: 10000`) |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` |
| `eureka.instance.prefer-ip-address` | `true` |

### `application-local.yml` (profile `local`)

Disables Eureka (`eureka.client.enabled: false`) and pins the simple discovery instances:

| Service | URI |
|---|---|
| `account-service` | `http://localhost:8081` |
| `charge-fee-service` | `http://localhost:8085` |
| `otp-service` | `http://localhost:8086` |
| `core-banking-service` | `http://localhost:8084` |
| `notification-service` | `http://localhost:8087` |

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/fund_transfer_db` | JDBC URL of the transfer database |
| `DB_USERNAME` | `banking_admin` | database user |
| `DB_PASSWORD` | `postgre2188` | database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Eureka registry URL |
| `ACCOUNT_SERVICE_URL` | `http://account-service` | base URL of Account Service |
| `CHARGE_FEE_SERVICE_URL` | `http://charge-fee-service` | base URL of Charge Fee Service |
| `OTP_SERVICE_URL` | `http://otp-service` | base URL of OTP Service |
| `CBS_SERVICE_URL` | `http://core-banking-service` | base URL of Core Banking Service |
| `NOTIFICATION_SERVICE_URL` | `http://notification-service` | base URL of Notification Service |
| `TRANSFER_NOTIFY_EMAIL` | `USER` | OTP recipient; `USER` = the address of the user who started the transaction |
| `TRANSFER_SAGA_ENABLED` | `false` | `true` = the saga flow (HTTP 202 + polling) |

Also honoured by Spring Boot relaxed binding: `SERVER_PORT`, `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` (these are what `run-all.sh` sets).

`run.env` in the repo root sets `TRANSFER_NOTIFY_EMAIL=USER` and `TRANSFER_SAGA_ENABLED=true`
(with the comment: *"The saga flow is the one the web UI understands: it returns 202 with status VALIDATING,
and the UI polls until the OTP is requested. With it turned off the UI refuses the response and never shows
the OTP box."*).

#### Environment variables you must set

For the web UI to work you want `TRANSFER_SAGA_ENABLED=true`. `TRANSFER_NOTIFY_EMAIL` should stay `USER`
unless you deliberately want every OTP to land in one inbox. The `*_SERVICE_URL` overrides are only
needed when the Eureka service names should not be used. `DB_PASSWORD` must match your PostgreSQL.

### Outbound HTTP client timeouts

`com.banking.fundtransfer.config.RestClientConfig` — every `RestClient` bean uses
`SimpleClientHttpRequestFactory` with `connectTimeout = 3000` ms and `readTimeout = 10000` ms.
The builders are `@LoadBalanced`, so the base URLs are Eureka service names.

## Behaviour Rules

### Row locking and atomic transitions

- `TransferRepository.findByIdForUpdate(id)` — `@Lock(LockModeType.PESSIMISTIC_WRITE)`;
  source comment: *"Locks the row until the end of the transaction, so events of one transfer are handled
  one at a time."*
- `TransferRepository.transition(id, userId, from, to, now)` — an **atomic** status change
  (`update ... where transferId = :id and userId = :userId and status = :from`). It returns **0** when
  the transfer was not in the expected status, which is how the service detects that another request got
  there first.
- `markOtpVerified(id, now)` — `update ... set otpVerified = true`. In the synchronous flow it is called
  **before** touching CBS: *"Recorded BEFORE touching CBS, so a retry after a crash never asks for the OTP again."*
- `setOtpId(id, otpId, now)`, `finish(id, status, reason, cbsId, now)`, `recover(...)`, `expireStale(...)`
  are all `@Modifying(clearAutomatically = true)` updates.

### Timeouts and retries (`TransferSagaEngine.handleTimeout`)

Timeouts per state (`timeoutSeconds(status)`):

| State | Timeout |
|---|---|
| `OTP_PENDING` | `app.saga.otp-issue-timeout-seconds` = 60 s |
| `AWAITING_OTP` | `app.transfer.pending-expiry-minutes` * 60 = 600 s |
| everything else | `app.saga.step-timeout-seconds` = 30 s |

The sweeper (`SagaTimeoutSweeper`, `@Scheduled(fixedDelayString = "${app.saga.sweep-interval-ms:5000}")`)
looks at `TransferSagaEngine.WATCHED` =
`VALIDATING, FEE_PENDING, BALANCE_CHECKING, OTP_PENDING, AWAITING_OTP, WITHDRAWING, DEPOSITING, REVERSING`
and picks up to **100** stale rows per status
(`findTop100ByFlowAndStatusAndStateEnteredAtBeforeOrderByStateEnteredAtAsc`).

What happens on a timeout:

| State | Action |
|---|---|
| `VALIDATING` | retry the `ValidateTransfer` command, up to `max-resends` (3); then `fail("VALIDATION_TIMEOUT", "Your accounts could not be checked in time. Please try again")` |
| `FEE_PENDING` | retry `CalculateFee`, up to 3; then `fail("FEE_TIMEOUT", "The fee could not be calculated in time. Please try again")` |
| `BALANCE_CHECKING` | retry `CheckBalance`, up to 3; then `fail("BALANCE_CHECK_TIMEOUT", "Your balance could not be checked in time. Please try again")` |
| `OTP_PENDING` | **no retry** — `fail("OTP_TIMEOUT", "The one-time password could not be sent in time. Please try again")` |
| `AWAITING_OTP` | move to `EXPIRED` (no retry) |
| `WITHDRAWING` | retry `Withdraw`, up to 3; then `escalate(STEP_TIMEOUT, "The withdrawal was not confirmed in time")` |
| `DEPOSITING` | retry `Deposit`, up to 3; then `escalate(STEP_TIMEOUT, "The deposit was not confirmed in time")` |
| `REVERSING` | retry `Reversal`, up to `reversal-max-resends` (5); then `escalate(STEP_TIMEOUT, "The reversal was not confirmed in time")` |

`STEP_TIMEOUT` = `"STEP_TIMEOUT"` (`TransferSagaEngine.STEP_TIMEOUT`); source comment: *"Failure code of a
transaction that was escalated only because CBS did not answer in time. It can heal itself."*
A transfer in `RECONCILIATION_REQUIRED` whose `failureCode` is `STEP_TIMEOUT` is **healable** — a late CBS
answer can still settle it (`healable(transfer)`).

Every retry calls `transfer.recordResend()` (increments `stepAttempts` and restarts the timeout clock) and
logs *"Transfer {} had no answer in {}, command sent again ({} of {})"*.

### The user-facing `message` strings (saga) — `TransferSagaService.messageFor`

| Status | Message |
|---|---|
| `VALIDATING` | `"Checking your accounts..."` (transfer) / `"Checking your account..."` (cash) |
| `FEE_PENDING` | `"Calculating the fee..."` |
| `BALANCE_CHECKING` | `"Checking your balance..."` |
| `OTP_PENDING` | `"Sending your one-time password..."` |
| `AWAITING_OTP` | `"Enter the one-time password we sent to your email"` |
| `WITHDRAWING` | `"Taking the money from your account..."` |
| `DEPOSITING` | `"Adding the money to your account..."` (deposit) / `"Sending the money to the receiver..."` (transfer) |
| `REVERSING` | `"The receiver could not be credited. Returning your money..."` |
| `COMPLETED`, `SUCCESS` | `"Deposit completed"` / `"Withdrawal completed"` / `"Transfer completed"` |
| `FAILED` | the `failureReason`, or `"The request failed"` |
| `REVERSED` | `"The transfer could not be completed and your money was returned"` (+ `": " + reason`) |
| `EXPIRED` | `"The request expired. Please start a new one"` |
| `RECONCILIATION_REQUIRED` | `"We are checking this request. Your money is safe. Please do not repeat it"` |
| `PENDING_OTP` | `"Waiting for the one-time password"` |
| `PROCESSING` | `"Processing..."` |
| `UNCONFIRMED` | `"The result is not confirmed yet. Please try again"` |

`finished` is `true` for `COMPLETED`, `FAILED`, `REVERSED`, `EXPIRED`, `SUCCESS` **and**
`RECONCILIATION_REQUIRED` (source comment: *"The screen can stop polling. RECONCILIATION_REQUIRED stops
polling too, but a person must finish it."*).

`fee` and `totalDebit` are `null` while a transfer is still in `VALIDATING` or `FEE_PENDING` — the fee
is not known yet.

### Failure / reason codes used by FTS

| Code | Where | Meaning |
|---|---|---|
| `INVALID_FEE` | `onFeeCalculated` | the fee service returned a fee that is null, negative or has more than 2 decimals |
| `OTP_DELIVERY_FAILED` | `onOtpDeliveryFailed` | the OTP could not be delivered |
| `AMOUNT_MISMATCH` | `onWithdrawCompleted`, `onDepositCompleted` | CBS reported different amounts than the request -> escalation |
| `REVERSAL_FAILED` | `onReversalFailed` | CBS refused the refund -> escalation, a person must finish it |
| `STEP_TIMEOUT` | `escalate` from `WITHDRAWING` / `DEPOSITING` / `REVERSING` | CBS did not answer in time -> escalation, can heal itself |
| `VALIDATION_TIMEOUT` | timeout in `VALIDATING` | Account Service did not answer in time |
| `FEE_TIMEOUT` | timeout in `FEE_PENDING` | Charge Fee Service did not answer in time |
| `BALANCE_CHECK_TIMEOUT` | timeout in `BALANCE_CHECKING` | CBS did not answer the balance check in time |
| `OTP_TIMEOUT` | timeout in `OTP_PENDING` | the OTP was not sent in time |

Plus every reason code CBS can send in a `FailureEvent`: `ACCOUNT_NOT_FOUND`, `ACCOUNT_INACTIVE`,
`INSUFFICIENT_FUNDS`, `SYSTEM_ACCOUNT_MISSING`, `NO_WITHDRAWAL`, `AMOUNT_MISMATCH`, `TRANSIT_SHORTFALL`,
`ALREADY_SETTLED`, `MISMATCH`. Those are stored verbatim in `transfers.failure_code` and
`transfers.failure_reason`.

`failureCode` is truncated to **50** chars, `failureReason` to **255** chars.

### The OTP flow

- `POST /api/transfers/{id}/resend-otp` -> `TransferSagaEngine.requestOtpResend`. The transfer must be in
  `AWAITING_OTP`, else `InvalidTransferException("A new code can only be requested while the transfer is
  waiting for the OTP")`. The cooldown is enforced against `lastOtpRequestedAt`:
  `ResendTooSoonException("Please wait " + wait + " seconds before requesting a new code")`.
- A wrong or missing OTP -> `OtpRejectedException` (**400**).
- The OTP is checked **synchronously** in the confirm call.
- A new code (first one or a resend) replaces the previous `otpId` — *"The OTP service invalidated the old code."*

### The notification recipient

`com.banking.fundtransfer.service.NotificationRecipient.resolve(configured, userEmail)`:

- `configured` is null, blank or `"USER"` (case-insensitive) -> use `userEmail`; if that is blank ->
  `IllegalStateException("No email address to send to: the transaction has no user address")`.
- otherwise -> use `configured` for **every** transaction.

### The synchronous (legacy) flow — `TransferServiceImpl`

- `initiate`: `from == to` -> `InvalidTransferException("Cannot transfer to the same account")`.
  The pending limit is checked (`countByUserIdAndStatusAndCreatedAtAfter(userId, PENDING_OTP, now - pendingExpiryMinutes)`;
  `>= max-pending-per-user` -> `TooManyPendingTransfersException`).
  Account ownership, status and currency are checked through Account Service; the fee through Charge Fee
  Service; and CBS is asked for the balance as an early check.
  The transfer is saved as `PENDING_OTP` and an OTP is sent; if the OTP service is down the transfer is
  marked `FAILED` with `"Could not send OTP"` and a `DownstreamUnavailableException` is thrown.
- `initiateDeposit`: no OTP; the transfer is saved as `PROCESSING` with `otpVerified = true` and the money
  is posted to CBS right away.
- `initiateWithdrawal`: still needs an OTP (`PENDING_OTP`).
- `confirm`: `SUCCESS` -> `"Transfer completed"`; `FAILED` -> the stored reason or `"Transfer failed"`;
  `EXPIRED` -> `TransferExpiredException`; `PROCESSING` -> `TransferInProgressException`;
  `PENDING_OTP` -> verify the OTP; `UNCONFIRMED` -> retry the posting.
  When the OTP service is unreachable the transfer goes back to `PENDING_OTP` so the user can try again.
- `post`: if CBS's outcome is `UNKNOWN`, the posting is retried once (safe because CBS ignores a repeated
  `referenceId`). `POSTED` -> `SUCCESS` + a `TransferCompleted` event; `REJECTED` -> `FAILED`;
  still unknown -> the transfer becomes `UNCONFIRMED` and `ResultUnconfirmedException` is thrown
  (*"Still unknown: do NOT mark it failed, the money may have moved. No event is written yet."*).

The CBS client maps HTTP **408 / 409 / 429** to `UNKNOWN` (they do not prove the posting failed) and
any other 4xx to `REJECTED` with the CBS `message` from the error body.

### Background jobs

| Job | Schedule | What it does |
|---|---|---|
| `OutboxRelay.publishPending` | every `app.messaging.relay-interval-ms` (1000 ms) | publishes the outbox to Kafka |
| `OutboxRelay.deleteOldPublishedEvents` | `cron "0 15 3 * * *"` | deletes published outbox rows older than 7 days |
| `ProcessedEventCleaner.clean` | `cron "0 30 3 * * *"` | deletes `processed_events` older than 14 days |
| `SagaTimeoutSweeper.sweep` | every `app.saga.sweep-interval-ms` (5000 ms) | finds stale saga transfers and applies the timeout rules |
| `TransferExpiryJob.expireStaleTransfers` | `fixedDelay = 60_000` | `PENDING_OTP` -> `EXPIRED` after `pending-expiry-minutes` |
| `TransferRecoveryRunner.run` | at startup | **currently a no-op** — the whole body is commented out. It was supposed to move `PROCESSING` -> `PENDING_OTP` (OTP not verified) and `PROCESSING` -> `UNCONFIRMED` (OTP verified) |
| `job/OutboxRelayJob` | – | **entirely commented out**; the active relay is `messaging/OutboxRelay` |

## Running It

Prerequisites:

- Java 17, Maven 3.9+ (the repo also ships `./mvnw`).
- PostgreSQL on `localhost:5433` with the database `fund_transfer_db`.
- Kafka on `localhost:9092` — the topics must exist; `event-bus-service` (port 8090) creates them.
- Eureka on `localhost:8761`.
- At request time: Account Service (8081), Charge Fee Service (8085), OTP Service (8086),
  Core Banking Service (8084) and Notification Service (8087).

The database schema repair must run first (`postgres/init/04-repair-schema.sql`) — `run-all.sh` does
this automatically on every start.

Build and run:

```bash
cd fund-transfer-service
mvn -q clean package -DskipTests     # -> target/fund-transfer-service-0.0.1-SNAPSHOT.jar
mvn spring-boot:run
```

Or from the jar:

```bash
java -jar target/fund-transfer-service-0.0.1-SNAPSHOT.jar
```

For a standalone run without Eureka, use the `local` profile:

```bash
SPRING_PROFILES_ACTIVE=local java -jar target/fund-transfer-service-0.0.1-SNAPSHOT.jar
```

The monorepo script: `./run-all.sh --build` (or `./run-all.sh` to reuse an existing jar). It sets
`SERVER_PORT=8082`, `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/fund_transfer_db`,
`SPRING_DATASOURCE_USERNAME=banking_admin`, `SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD`.

Environment:

```bash
export DB_URL='jdbc:postgresql://localhost:5433/fund_transfer_db'
export DB_USERNAME='banking_admin'
export DB_PASSWORD='postgre2188'
export KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
export EUREKA_URL='http://localhost:8761/eureka'
export TRANSFER_SAGA_ENABLED='true'
export TRANSFER_NOTIFY_EMAIL='USER'
```

Check that it is healthy:

```bash
curl -s http://localhost:18082/actuator/health
curl -s http://localhost:18082/actuator/prometheus | grep -E 'transfers_|saga_|outbox_'
```

The actuator is **not** exposed on the public port 8082.

Stop it with `Ctrl-C`, or `pkill -f fund-transfer-service`.

## API Quick Reference (curl examples)

The gateway sets `X-User-Id` and `X-User-Email` from the JWT, so a direct call to the service must send
them itself. Through the gateway you send only the Bearer token.

```bash
TOKEN='<jwt>'

# 1. Initiate a transfer (202 Accepted in the saga flow)
curl -s -i -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountNumber":"ACC-1001","toAccountNumber":"ACC-1002","amount":25.00}'

# 2. Poll the transfer until the OTP is requested
curl -s http://localhost:8080/api/transfers/TXN-0123456789ABCDEF \
  -H "Authorization: Bearer $TOKEN"

# 3. Confirm with the one-time password (200 OK when finished, 202 when still running)
curl -s -i -X POST http://localhost:8080/api/transfers/TXN-0123456789ABCDEF/confirm \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"otp":"123456"}'

# 4. Request a new code (202 Accepted)
curl -s -i -X POST http://localhost:8080/api/transfers/TXN-0123456789ABCDEF/resend-otp \
  -H "Authorization: Bearer $TOKEN"

# 5. Cash deposit (202 Accepted in the saga flow)
curl -s -i -X POST http://localhost:8080/api/transfers/deposit \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC-1001","amount":100.00}'

# 6. Cash withdrawal (202 Accepted in the saga flow)
curl -s -i -X POST http://localhost:8080/api/transfers/withdraw \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC-1001","amount":50.00}'

# 7. The 100 most recent transfers of the caller
curl -s http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN"
```

Calling the service directly (no gateway) needs the identity headers:

```bash
curl -s http://localhost:8082/api/transfers \
  -H 'X-User-Id: 1' \
  -H 'X-User-Email: user@example.com'
```

## Configuration Reference (quick table)

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8082` | HTTP port |
| `management.server.port` | `18082` | actuator port |
| `app.transfer.saga-enabled` | `false` (`TRANSFER_SAGA_ENABLED`) | `true` = saga flow, `202` + polling; `false` = the synchronous flow, `200` |
| `app.transfer.pending-expiry-minutes` | `10` | how long an unconfirmed transfer may stay open |
| `app.transfer.max-pending-per-user` | `5` | unconfirmed transfers per user |
| `app.transfer.notify-email` | `USER` (`TRANSFER_NOTIFY_EMAIL`) | OTP recipient; `USER` = the address of the user who started the transfer |
| `app.transfer.deposit-requires-otp` | `false` | whether a cash deposit needs the one-time password |
| `app.saga.step-timeout-seconds` | `30` | how long a step waits for its answer |
| `app.saga.max-resends` | `3` | resends of a step's command |
| `app.saga.reversal-max-resends` | `5` | resends of the reversal command |
| `app.saga.otp-issue-timeout-seconds` | `60` | how long `OTP_PENDING` waits for the code |
| `app.saga.otp-resend-cooldown-seconds` | `30` | minimum time between two OTP requests |
| `app.saga.sweep-interval-ms` | `5000` | how often the timeout sweeper runs |
| `app.services.account` | `http://account-service` (`ACCOUNT_SERVICE_URL`) | Account Service base URL |
| `app.services.charge-fee` | `http://charge-fee-service` (`CHARGE_FEE_SERVICE_URL`) | Charge Fee Service base URL |
| `app.services.otp` | `http://otp-service` (`OTP_SERVICE_URL`) | OTP Service base URL |
| `app.services.cbs` | `http://core-banking-service` (`CBS_SERVICE_URL`) | Core Banking Service base URL |
| `app.services.notification` | `http://notification-service` (`NOTIFICATION_SERVICE_URL`) | Notification Service base URL |
| `app.messaging.producer-name` | `fund-transfer-service` | `producer` field of every envelope |
| `app.messaging.relay-interval-ms` | `1000` | outbox relay poll interval |
| `spring.datasource.url` | `${DB_URL:jdbc:postgresql://localhost:5433/fund_transfer_db}` | database URL |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` | database user |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` | database password |
| `spring.jpa.hibernate.ddl-auto` | `update` | schema is created/updated by Hibernate |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | broker list |
| `spring.kafka.consumer.group-id` | `fund-transfer-service` | consumer group |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | start from the beginning on a new group |
| `spring.kafka.listener.concurrency` | `3` | consumer threads |
| `spring.kafka.producer.acks` | `all` | full durability |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | Eureka registry |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `401 Unauthorized`, `"Missing user identity"` | `X-User-Id` (or `X-User-Email` on a POST) is missing | Go through the API gateway with `Authorization: Bearer <jwt>`, or send the headers directly |
| `404 "Not found"` from the gateway | The path is not `/api/transfers/**` | The gateway only routes `/api/transfers/**` to this service |
| `400`, `"fromAccountNumber: fromAccountNumber is required"` | The request body is missing a field | Send `fromAccountNumber`, `toAccountNumber` and `amount` |
| `400`, `"otp must be exactly 6 digits"` | The OTP is not 6 digits | Send exactly six digits |
| `400`, `"Cannot transfer to the same account"` | `fromAccountNumber == toAccountNumber` | Use two different accounts |
| `403 Forbidden`, `"You do not own the source account"` | The account belongs to somebody else | Ownership is always checked against Account Service |
| `404`, `"Account not found: ACC-1001"` | Account Service does not know the account | Create the account in Account Service first |
| `429`, `"You have too many unconfirmed transfers. Confirm or wait for them to expire"` | More than `max-pending-per-user` (5) open transfers | Confirm one, or wait `pending-expiry-minutes` (10) |
| `429`, `"Please wait N seconds before requesting a new code"` | The OTP resend cooldown (30 s) has not passed | Wait, then call `resend-otp` again |
| `409`, `"Your request is still being checked, please wait a moment"` | The transfer is in `VALIDATING`, `FEE_PENDING` or `BALANCE_CHECKING` | Poll `GET /api/transfers/{id}` instead of confirming |
| `409`, `"The one-time password is still being sent, please wait a moment"` | The transfer is in `OTP_PENDING` | Poll `GET /api/transfers/{id}` |
| `400`, `"OTP is required"` | No `otp` in the confirm body | Send `{"otp":"123456"}` |
| `503`, `"We could not confirm the result yet. Press Confirm again in a moment, or check History. Do not start a new transfer."` | The synchronous flow could not tell whether CBS posted the money | Press Confirm again — do **not** start a new transfer |
| `503`, `"Banking core is unavailable, please try again"` | `core-banking-service` is down or unreachable | Check that port 8084 is up and `CBS_SERVICE_URL` is right |
| `503`, `"Account service is unavailable, please try again"` | `account-service` is down | Check that port 8081 is up |
| `503`, `"Could not send the OTP, please try again"` | `otp-service` is down | Check that port 8086 is up |
| `500`, `"Something went wrong"` | An unhandled exception; the stack trace is in the service log | Read the log of the service on port 8082 |
| `new row for relation transfers violates check constraint transfers_status_check` | The database has an old constraint that does not allow the saga statuses | Run `postgres/init/04-repair-schema.sql` (or `./run-all.sh`) |
| A transfer stays in `VALIDATING` forever | Account Service did not answer, or the outbox never drained | Check `outbox.pending.events` on port 18082 and the `saga.events` counter |
| A transfer sits in `BALANCE_CHECKING` for 30 s and then fails with `BALANCE_CHECK_TIMEOUT` | CBS did not answer the `CheckBalance` command | Check `core-banking-service` and the `cbs.commands` topic |
| A transfer is in `RECONCILIATION_REQUIRED` | CBS answered with a different amount, refused a reversal, or did not answer in time | A person has to finish it; a late CBS answer may still settle a `STEP_TIMEOUT` case |
| The OTP e-mail arrives at the wrong inbox | `TRANSFER_NOTIFY_EMAIL` is set to an address instead of `USER` | Set `TRANSFER_NOTIFY_EMAIL=USER` |

## Notes and Known Limitations

- **The saga path is the live path.** `run.env` sets `TRANSFER_SAGA_ENABLED=true`; the web UI only
  understands the 202 + poll flow. With the flag off, the controller returns 200 and the synchronous body.
- **The flow of an existing transfer is decided by the row** (`transfers.flow = "SAGA"`), not by the setting,
  so toggling the flag never strands a running transfer.
- **The `transfers_status_check` constraint must contain all 17 saga statuses**; the repair script
  `postgres/init/04-repair-schema.sql` enforces this on every start of `run-all.sh`.
- **`TransferRecoveryRunner` is a no-op.** The whole body is commented out, so a transfer that was in
  `PROCESSING` when the service stopped is **not** recovered at startup. It stays where it is.
- **`job/OutboxRelayJob` is entirely commented out.** The active relay is `messaging/OutboxRelay`, and the
  settings `app.outbox.poll-interval-ms` and `app.outbox.retention-days` are only referenced by the dead
  class — the active relay hard-codes the 7-day retention.
- **There are two outbox repositories.** `repository/OutboxEventRepository` (`JpaRepository<OutboxEvent, String>`)
  does not match the entity's `UUID` id and is only used by `TransferOutcomeRecorder`. The active path is
  `messaging/OutboxRepository` (`JpaRepository<OutboxEvent, UUID>`).
- **`app.transfer.notify-email` is a demo setting.** Any value other than `USER` sends *every* OTP of
  *every* user to that one address, and the real user never receives the code.
- **The service does not validate the JWT itself.** It trusts the gateway to set `X-User-Id` and
  `X-User-Email`. Calling port 8082 directly with a forged `X-User-Id` would be accepted — do not expose
  port 8082 outside the internal network.
- **The OTP is verified synchronously** in the confirm call, but the *delivery* is asynchronous: the
  service asks the OTP Service to send a code and waits for the `OtpIssued` event.
- **`app.saga.sweep-interval-ms` is not a field of `SagaProperties`.** It is read straight from the
  placeholder in `SagaTimeoutSweeper`, so it will not appear in a bound-properties dump.
- **The `transfers` table grows forever.** Only `processed_events` (older than 14 days) and the published
  outbox rows (older than 7 days) are cleaned up.
