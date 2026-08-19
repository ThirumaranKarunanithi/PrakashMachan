package com.ledgerintegrity.platform.engagement;

import com.ledgerintegrity.platform.auth.persist.AuditLogEntry;
import com.ledgerintegrity.platform.auth.persist.AuditLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SEC-006 / CDC-008: secure deletion of an engagement and everything under it, in one
 * transaction, producing an auditable completion record. Deletion is an ADMIN action
 * (enforced at the API layer) and cannot be partial — either everything goes or nothing.
 */
@Service
public class EngagementDeletionService {

    /** Every table that carries engagement-scoped data, children before parents. */
    private static final List<String> ENGAGEMENT_TABLES = List.of(
            "sample_selections", "benford_runs",
            "gst_manual_matches", "gst_match_results",
            "gstr3b_summaries", "gstr1_invoices", "gstr2b_invoices",
            "sales_invoices", "purchase_invoices",
            "bank_manual_matches", "bank_match_results", "bank_ledger_lines", "bank_statement_lines",
            "audit_trail_events", "vendor_records",
            "exception_cases", "investigation_cases", "rule_runs",
            "workpapers", "evidence_requests",
            "import_batches", "ledger_entries");

    private final JdbcTemplate jdbc;
    private final EngagementRepository engagements;
    private final AuditLogRepository auditLog;

    public EngagementDeletionService(JdbcTemplate jdbc, EngagementRepository engagements,
                                     AuditLogRepository auditLog) {
        this.jdbc = jdbc;
        this.engagements = engagements;
        this.auditLog = auditLog;
    }

    @Transactional
    public int deleteEngagement(UUID engagementId, UUID firmId, String deletedBy) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));

        int total = 0;
        // evidence documents hang off requests, not the engagement directly
        total += jdbc.update("delete from evidence_documents where request_id in "
                + "(select id from evidence_requests where engagement_id = ?)", engagementId);
        // element collections of import batches
        total += jdbc.update("delete from import_batch_issues where batch_id in "
                + "(select id from import_batches where engagement_id = ?)", engagementId);
        total += jdbc.update("delete from import_batch_files where batch_id in "
                + "(select id from import_batches where engagement_id = ?)", engagementId);
        // client portal users bound to this engagement
        total += jdbc.update("delete from app_users where engagement_id = ?", engagementId);
        for (String table : ENGAGEMENT_TABLES) {
            total += jdbc.update("delete from " + table + " where engagement_id = ?", engagementId);
        }
        engagements.delete(engagement);

        // SEC-006: deletion produces an auditable completion record
        auditLog.save(new AuditLogEntry(firmId, deletedBy, "DELETE",
                "/engagements/" + engagementId + " [SECURE-DELETE " + engagement.getClientName()
                        + ", " + total + " child rows removed]", 200, Instant.now()));
        return total;
    }
}
