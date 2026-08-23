package com.ledgerintegrity.platform.engagement;

/** Optional (add-on) modules; everything not listed here is part of Core. */
public enum Module {
    GST("Prametra GST (GST Reconciliation)"),
    BANK("Prametra Bank (Bank Reconciliation & Cash Intelligence)"),
    VENDOR("Prametra Vendor (Vendor & Payment Analytics)"),
    AUDIT_TRAIL("Prametra Trail (Audit Trail & Management Override)");

    private final String displayName;

    Module(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
