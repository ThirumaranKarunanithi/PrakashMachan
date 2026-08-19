package com.ledgerintegrity.platform.vendor;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.rules.ExceptionService;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEvent;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ATR-002/003 (BRD §10): audit-trail completeness and configuration analysis.
 *  - Coverage gaps: periods of the financial year with no logged events. A gap is a
 *    coverage limitation to state, never proof that logging was disabled (§10 boundary).
 *  - Configuration events: entries that suggest the audit trail itself was switched
 *    off, bypassed or weakened — these are HIGH-priority review items.
 */
@Service
public class AuditTrailAnalysisService {

    /** A quiet stretch at least this long is reported as a coverage gap. */
    static final int GAP_THRESHOLD_DAYS = 30;

    public record Gap(LocalDate from, LocalDate to, long days) {}

    public record CompletenessReport(int events, LocalDateTime firstEvent, LocalDateTime lastEvent,
                                     List<Gap> gaps, List<String> monthsWithoutEvents,
                                     Map<String, Long> eventsByObject,
                                     int disablementEvents,
                                     int exceptionsCreated, int skippedExisting) {}

    private final EngagementRepository engagements;
    private final AuditTrailEventRepository auditTrail;
    private final ExceptionService exceptionService;

    public AuditTrailAnalysisService(EngagementRepository engagements,
                                     AuditTrailEventRepository auditTrail,
                                     ExceptionService exceptionService) {
        this.engagements = engagements;
        this.auditTrail = auditTrail;
        this.exceptionService = exceptionService;
    }

    /** Report-only view for the workpaper (ATR-007): no exceptions are raised. */
    public CompletenessReport reportOnly(UUID engagementId) {
        return analyze(engagementId, false);
    }

    @Transactional
    public CompletenessReport analyze(UUID engagementId) {
        return analyze(engagementId, true);
    }

    @Transactional
    public CompletenessReport analyze(UUID engagementId, boolean raiseExceptions) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));
        List<AuditTrailEvent> events = auditTrail.findByEngagementId(engagementId).stream()
                .sorted(Comparator.comparing(AuditTrailEvent::getTimestamp))
                .toList();

        List<Finding> findings = new ArrayList<>();
        Map<String, Long> byObject = new TreeMap<>();
        int disablement = 0;
        for (AuditTrailEvent e : events) {
            byObject.merge(e.getObjectType(), 1L, Long::sum);
            if (isConfigurationEvent(e)) {
                disablement++;
                findings.add(new Finding("ATR-03", "Audit-trail configuration event", Finding.Severity.HIGH, 0,
                        "Event at " + e.getTimestamp() + " by " + e.getUserId() + " (" + e.getAction() + " "
                                + e.getObjectType() + "." + e.getField()
                                + ") suggests the audit trail itself was changed or weakened. Confirm what was"
                                + " affected, for how long, and whether it was authorised.",
                        List.of("ATR:CFG:" + e.getTimestamp()),
                        e.getSourceFile() + ":" + e.getSourceRow()));
            }
        }

        // coverage gaps across the financial year, including lead-in and tail
        List<Gap> gaps = new ArrayList<>();
        LocalDate fyStart = engagement.getFyStart();
        LocalDate closeDate = engagement.getCloseDate();
        if (!events.isEmpty()) {
            LocalDate cursor = fyStart;
            for (AuditTrailEvent e : events) {
                LocalDate d = e.getTimestamp().toLocalDate();
                if (d.isBefore(fyStart)) continue;
                addGapIfLong(gaps, cursor, d);
                cursor = d.isAfter(cursor) ? d : cursor;
            }
            if (cursor.isBefore(closeDate)) addGapIfLong(gaps, cursor, closeDate);
        } else {
            addGapIfLong(gaps, fyStart, closeDate);
        }
        for (Gap g : gaps) {
            findings.add(new Finding("ATR-02", "Audit-trail coverage gap", Finding.Severity.MEDIUM, 0,
                    "No audit-trail events for " + g.days() + " days (" + g.from() + " to " + g.to() + ")."
                            + " Absence of events is not proof that logging was disabled — confirm whether the"
                            + " export covers this period and state the coverage limitation in the workpaper.",
                    List.of("ATR:GAP:" + g.from()),
                    "audit-trail population"));
        }

        // months of the FY with zero events
        List<String> quietMonths = new ArrayList<>();
        LocalDate m = fyStart.withDayOfMonth(1);
        while (!m.isAfter(closeDate)) {
            String key = m.toString().substring(0, 7);
            boolean any = events.stream().anyMatch(e -> e.getTimestamp().toLocalDate().toString().startsWith(key));
            if (!any) quietMonths.add(key);
            m = m.plusMonths(1);
        }

        ExceptionService.RaiseResult raised = raiseExceptions
                ? exceptionService.raise(engagementId, UUID.randomUUID(), findings)
                : new ExceptionService.RaiseResult(List.of(), 0);
        return new CompletenessReport(events.size(),
                events.isEmpty() ? null : events.get(0).getTimestamp(),
                events.isEmpty() ? null : events.get(events.size() - 1).getTimestamp(),
                gaps, quietMonths, byObject, disablement,
                raised.created().size(), raised.skipped());
    }

    private static void addGapIfLong(List<Gap> gaps, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days >= GAP_THRESHOLD_DAYS) gaps.add(new Gap(from, to, days));
    }

    /** Events whose object, field or action suggests logging configuration was touched. */
    static boolean isConfigurationEvent(AuditTrailEvent e) {
        String haystack = (e.getObjectType() + " " + e.getField() + " " + e.getAction()
                + " " + (e.getNewValue() == null ? "" : e.getNewValue())).toLowerCase(Locale.ROOT);
        return haystack.contains("audit") || haystack.contains("logging") || haystack.contains("edit log")
                || e.getAction().toLowerCase(Locale.ROOT).contains("disable");
    }
}
