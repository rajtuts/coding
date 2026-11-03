package com.example.payments;

/** Placeholder for a real rules/ML engine. Runs before any money moves. */
public class RiskService {

    public enum Decision { APPROVE, DECLINE }

    private static final long MAX_AMOUNT = 5_000_000;   // Rs 50,000 in paise

    public Decision score(Payment p) {
        if (p.amount() > MAX_AMOUNT) return Decision.DECLINE;
        if (p.paymentToken().startsWith("pm_stolen")) return Decision.DECLINE;
        return Decision.APPROVE;
    }
}
