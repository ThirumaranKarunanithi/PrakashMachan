package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequestRepository;
import com.ledgerintegrity.platform.importer.persist.LedgerEntry;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEvent;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEventRepository;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;
import com.ledgerintegrity.platform.vendor.persist.VendorRecordRepository;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AC-08: the complete chronological story of one investigation case across every
 * source — voucher postings and creations, vendor master changes from the audit
 * trail, evidence requests, and auditor decisions.
 */
@Service
public class CaseTimelineService {

    public record TimelineEvent(String when, String source, String description) {}

    private final LedgerEntryRepository entries;
    private final ExceptionCaseRepository exceptions;
    private final VendorRecordRepository vendors;
    private final AuditTrailEventRepository auditTrail;
    private final EvidenceRequestRepository evidenceRequests;

    public CaseTimelineService(LedgerEntryRepository entries,
                               ExceptionCaseRepository exceptions,
                               VendorRecordRepository vendors,
                               AuditTrailEventRepository auditTrail,
                               EvidenceRequestRepository evidenceRequests) {
        this.entries = entries;
        this.exceptions = exceptions;
        this.vendors = vendors;
        this.auditTrail = auditTrail;
        this.evidenceRequests = evidenceRequests;
    }

    public List<TimelineEvent> timeline(InvestigationCase c) {
        List<ExceptionCase> members = exceptions
                .findByEngagementIdOrderBySeverityAscExposurePaiseDesc(c.getEngagementId()).stream()
                .filter(x -> c.getId().equals(x.getCaseId()))
                .toList();

        Set<String> voucherTokens = new LinkedHashSet<>();
        Set<String> vendorTokens = new LinkedHashSet<>();
        for (String token : c.getVoucherIds().split(" ")) {
            if (token.startsWith("VENDOR:")) vendorTokens.add(token.substring("VENDOR:".length()));
            else voucherTokens.add(token);
        }

        List<TimelineEvent> events = new ArrayList<>();

        // voucher postings and creations
        Map<String, List<Voucher>> byId = new HashMap<>();
        Voucher.group(entries.findByEngagementId(c.getEngagementId()).stream()
                .map(LedgerEntry::toRow).toList())
                .forEach(v -> byId.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(v));
        for (String token : voucherTokens) {
            for (Voucher v : byId.getOrDefault(token, List.of())) {
                events.add(new TimelineEvent(v.txnDate() + "T00:00", "ledger",
                        "Voucher " + v.id() + " (" + v.type() + ") dated for Rs "
                                + rupees(v.amountPaise()) + " by " + v.userId()));
                if (v.createdAt() != null && !v.createdAt().toLocalDate().equals(v.txnDate())) {
                    events.add(new TimelineEvent(v.createdAt().toString(), "ledger",
                            "Voucher " + v.id() + " actually created (posting lag)"));
                }
            }
        }

        // vendor master history
        for (VendorRecord v : vendors.findByEngagementId(c.getEngagementId())) {
            if (!vendorTokens.contains(v.getVendorId())) continue;
            if (v.getCreatedDate() != null) {
                events.add(new TimelineEvent(v.getCreatedDate() + "T00:00", "vendor master",
                        "Vendor " + v.getVendorId() + " \"" + v.getName() + "\" created by " + v.getCreatedBy()));
            }
            for (AuditTrailEvent e : auditTrail.findByEngagementId(c.getEngagementId())) {
                if (!v.getVendorId().equals(e.getRecordId())) continue;
                events.add(new TimelineEvent(e.getTimestamp().toString(), "audit trail",
                        e.getUserId() + " " + e.getAction().toLowerCase() + " " + e.getField()
                                + " (" + e.getOldValue() + " → " + e.getNewValue() + ")"));
            }
        }

        // exception decisions and evidence requests
        for (ExceptionCase x : members) {
            if (x.getDecidedAt() != null) {
                events.add(new TimelineEvent(x.getDecidedAt().atZone(ZoneOffset.UTC).toLocalDateTime().toString(),
                        "review", x.getRuleId() + " marked " + x.getStatus() + " by " + x.getDecidedBy()
                        + (x.getDecisionNote() == null ? "" : ": " + x.getDecisionNote())));
            }
            for (EvidenceRequest r : evidenceRequests
                    .findByEngagementIdOrderByCreatedAtDesc(c.getEngagementId())) {
                if (!r.getExceptionId().equals(x.getId())) continue;
                events.add(new TimelineEvent(r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDateTime().toString(),
                        "evidence", "Requested: \"" + r.getTitle() + "\" (" + r.getStatus() + ")"));
            }
        }

        events.sort(Comparator.comparing(TimelineEvent::when));
        return events;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
