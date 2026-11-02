package com.banking.fundtransfer.service;

/**
 * Works out which address an email about a transaction goes to.
 *
 * The setting app.transfer.notify-email decides:
 * - USER, empty or missing: the address of the user who started the
 *   transaction.
 * - any other value: that address, for every transaction. Useful for a
 *   demo, but it means the real user never receives the OTP.
 *
 * A blank or missing user address is an error. Sending to it would
 * fail later inside the notification service, where the reason is much
 * harder to see.
 */
public final class NotificationRecipient {

    private NotificationRecipient() {}

    public static String resolve(String configured, String userEmail) {
        if (configured == null || configured.isBlank() || "USER".equalsIgnoreCase(configured.trim())) {
            if (userEmail == null || userEmail.isBlank()) {
                throw new IllegalStateException(
                        "No email address to send to: the transaction has no user address");
            }
            return userEmail.trim();
        }
        return configured.trim();
    }
}
