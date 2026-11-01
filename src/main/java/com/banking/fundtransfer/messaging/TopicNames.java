package com.banking.fundtransfer.messaging;

public final class TopicNames {

    private TopicNames() {}

    public static final String ACCOUNT_COMMANDS = "account.commands";
    public static final String ACCOUNT_EVENTS = "account.events";
    public static final String FEE_COMMANDS = "fee.commands";
    public static final String FEE_EVENTS = "fee.events";
    public static final String OTP_COMMANDS = "otp.commands";
    public static final String OTP_EVENTS = "otp.events";
    public static final String CBS_COMMANDS = "cbs.commands";
    public static final String CBS_EVENTS = "cbs.events";
    public static final String TRANSFER_EVENTS = "transfer.events";
    public static final String NOTIFICATION_EVENTS = "notification.events";

    /** A message that failed all its retries is parked on "<topic>.DLT". */
    public static final String DLT_SUFFIX = ".DLT";
}