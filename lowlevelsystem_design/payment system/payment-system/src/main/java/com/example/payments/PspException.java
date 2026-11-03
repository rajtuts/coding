package com.example.payments;

/**
 * Failure while talking to a PSP.
 * outcomeUnknown=true  -> request may have been processed (read timeout): never assume failure.
 * outcomeUnknown=false -> request definitely not processed (connection refused): safe to fail.
 */
public class PspException extends RuntimeException {
    private final boolean outcomeUnknown;

    public PspException(String message, boolean outcomeUnknown) {
        super(message);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() { return outcomeUnknown; }
}
