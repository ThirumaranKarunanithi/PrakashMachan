package com.ledgerintegrity.platform.importer.model;

/** One data-quality problem found during import (DAT-003). */
public record QualityIssue(
        IssueType type,
        String field,
        String value,
        String message,
        Lineage lineage
) {
    public enum IssueType {
        MISSING_REQUIRED_FIELD,
        INVALID_DATE,
        INVALID_TIMESTAMP,
        NON_NUMERIC_AMOUNT,
        BOTH_DEBIT_AND_CREDIT,
        NO_AMOUNT,
        DUPLICATE_ROW_IDENTITY,
        UNMAPPED_ACCOUNT,
        UNMAPPED_COLUMN,
        TB_NOT_PROVIDED
    }
}
