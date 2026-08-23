package com.ledgerintegrity.platform.engagement;

/** Optional (add-on) modules; everything not listed here is part of Core. */
public enum Module {
    GST("GST Reconciliation"),
    BANK("Bank Reconciliation & Cash Intelligence"),
    VENDOR("Vendor & Payment Analytics"),
    AUDIT_TRAIL("Audit Trail & Management Override");

    private final String displayName;

    Module(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
