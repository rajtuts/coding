package com.example.payments;

import java.util.UUID;

public final class Ids {
    private Ids() { }

    /** Roughly time-sortable id with a type prefix, e.g. pay_lx3k9q2a1b2c3d4e5f6. */
    public static String newId(String prefix) {
        return prefix + "_" + Long.toString(System.currentTimeMillis(), 36)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
