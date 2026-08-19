package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.importer.MappingProfile.DateFormat;
import com.ledgerintegrity.platform.importer.MappingProfile.SourceType;
import com.ledgerintegrity.platform.importer.MappingProfile.StandardField;

import java.util.Map;

final class TestData {

    private TestData() {}

    static MappingProfile clientAProfile() {
        return new MappingProfile("client-a-gl", SourceType.CSV, "test profile", DateFormat.ISO, Map.ofEntries(
                Map.entry(StandardField.VOUCHER_ID, "voucher_id"),
                Map.entry(StandardField.VOUCHER_TYPE, "voucher_type"),
                Map.entry(StandardField.TXN_DATE, "txn_date"),
                Map.entry(StandardField.CREATED_AT, "created_at"),
                Map.entry(StandardField.ACCOUNT_CODE, "account_code"),
                Map.entry(StandardField.ACCOUNT_NAME, "account_name"),
                Map.entry(StandardField.DEBIT, "debit"),
                Map.entry(StandardField.CREDIT, "credit"),
                Map.entry(StandardField.NARRATION, "narration"),
                Map.entry(StandardField.SOURCE, "source"),
                Map.entry(StandardField.USER_ID, "user_id"),
                Map.entry(StandardField.REVERSAL_OF, "reversal_of")));
    }

    static final String GL_HEADER =
            "voucher_id,voucher_type,txn_date,created_at,account_code,account_name,debit,credit,narration,source,user_id,reversal_of";
}
