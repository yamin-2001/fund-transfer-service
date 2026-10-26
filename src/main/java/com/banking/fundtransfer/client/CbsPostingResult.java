package com.banking.fundtransfer.client;

public record CbsPostingResult(Outcome outcome, String transactionId, String message) {

    public enum Outcome {
        POSTED,
        REJECTED,
        UNKNOWN
    }

    public static CbsPostingResult posted(String transactionId) {
        return new CbsPostingResult(Outcome.POSTED, transactionId, null);
    }

    public static CbsPostingResult rejected(String message) {
        return new CbsPostingResult(Outcome.REJECTED, null, message);
    }

    public static CbsPostingResult unknown() {
        return new CbsPostingResult(Outcome.UNKNOWN, null, null);
    }
}