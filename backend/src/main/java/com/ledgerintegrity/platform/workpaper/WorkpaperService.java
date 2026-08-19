package com.ledgerintegrity.platform.workpaper;

import com.ledgerintegrity.platform.bank.persist.BankMatchResult;
import com.ledgerintegrity.platform.bank.persist.BankMatchResultRepository;
import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.importer.persist.ImportBatch;
import com.ledgerintegrity.platform.importer.persist.ImportBatchRepository;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.notify.NotificationService;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.rules.persist.RuleRun;
import com.ledgerintegrity.platform.rules.persist.RuleRunRepository;
import com.ledgerintegrity.platform.rules.persist.SampleSelection;
import com.ledgerintegrity.platform.rules.persist.SampleSelectionRepository;
import com.ledgerintegrity.platform.vendor.AuditTrailAnalysisService;
import com.ledgerintegrity.platform.workpaper.persist.Workpaper;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplate;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplateRepository;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the engagement workpaper from persisted platform state (BRD §15):
 * data & integrity (AWP-002), procedures & exact parameters (AWP-003), the
 * consolidated exception register with decisions (AWP-004). Content is HTML —
 * openable in Word/browsers (AWP-007) — and snapshotted with a SHA-256.
 * Sign-off and locking live on the Workpaper entity (AWP-005/006).
 */
@Service
public class WorkpaperService {

    private final EngagementRepository engagements;
    private final ImportBatchRepository batches;
    private final LedgerEntryRepository entries;
    private final RuleRunRepository ruleRuns;
    private final InvestigationCaseRepository cases;
    private final ExceptionCaseRepository exceptions;
    private final GstMatchResultRepository gstMatches;
    private final BankMatchResultRepository bankMatches;
    private final WorkpaperRepository workpapers;
    private final NotificationService notificationService;
    private final WorkpaperTemplateRepository templates;
    private final SampleSelectionRepository samples;
    private final AuditTrailAnalysisService atrAnalysis;

    public WorkpaperService(EngagementRepository engagements,
                            ImportBatchRepository batches,
                            LedgerEntryRepository entries,
                            RuleRunRepository ruleRuns,
                            InvestigationCaseRepository cases,
                            ExceptionCaseRepository exceptions,
                            GstMatchResultRepository gstMatches,
                            BankMatchResultRepository bankMatches,
                            WorkpaperRepository workpapers,
                            NotificationService notificationService,
                            WorkpaperTemplateRepository templates,
                            SampleSelectionRepository samples,
                            AuditTrailAnalysisService atrAnalysis) {
        this.templates = templates;
        this.samples = samples;
        this.atrAnalysis = atrAnalysis;
        this.notificationService = notificationService;
        this.engagements = engagements;
        this.batches = batches;
        this.entries = entries;
        this.ruleRuns = ruleRuns;
        this.cases = cases;
        this.exceptions = exceptions;
        this.gstMatches = gstMatches;
        this.bankMatches = bankMatches;
        this.workpapers = workpapers;
    }

    @Transactional
    public Workpaper generate(UUID engagementId) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));
        int nextVersion = workpapers.findByEngagementIdOrderByVersionDesc(engagementId).stream()
                .mapToInt(Workpaper::getVersion).max().orElse(0) + 1;

        String html = render(engagement, nextVersion);
        Workpaper wp = new Workpaper(UUID.randomUUID(), engagementId, nextVersion,
                "Engagement workpaper — " + engagement.getClientName() + " FY "
                        + engagement.getFyStart() + " to " + engagement.getFyEnd(),
                html, Checksums.sha256Hex(html), Instant.now());
        workpapers.save(wp);
        return wp;
    }

    @Transactional
    public Workpaper sign(UUID workpaperId, Workpaper.Role role, String name) {
        Workpaper wp = workpapers.findById(workpaperId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workpaper: " + workpaperId));
        wp.sign(role, name, Instant.now());
        workpapers.save(wp);
        engagements.findById(wp.getEngagementId()).ifPresent(e ->
                notificationService.notifyOnce(e.getFirmId(), "WORKPAPER",
                        wp.getId() + ":" + wp.getStatus(),
                        "Workpaper v" + wp.getVersion() + " for " + e.getClientName() + " is now "
                                + wp.getStatus() + " (" + role + ": " + name.trim() + ").", null));
        return wp;
    }

    // ---------- rendering ----------

    private String render(Engagement e, int version) {
        UUID id = e.getId();
        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<html><head><meta charset='utf-8'><title>Workpaper</title><style>")
                .append("body{font-family:Calibri,Arial,sans-serif;font-size:11pt;margin:32px;color:#1a2233}")
                .append("h1{font-size:16pt}h2{font-size:13pt;margin-top:22px;border-bottom:1px solid #ccc}")
                .append("table{border-collapse:collapse;width:100%;margin:8px 0;font-size:10pt}")
                .append("th,td{border:1px solid #bbb;padding:4px 7px;text-align:left;vertical-align:top}")
                .append("th{background:#eef1f5}.num{text-align:right}.small{font-size:8pt;color:#555}")
                .append("</style></head><body>");

        // 1. header — AWP-001: the firm template controls wording and sections
        WorkpaperTemplate tpl = templates.findTopByFirmIdOrderByVersionDesc(e.getFirmId()).orElse(null);
        String headerTitle = tpl == null ? "Engagement Workpaper" : tpl.getHeaderTitle();
        h.append("<h1>").append(esc(headerTitle + " — " + e.getClientName())).append("</h1>");
        h.append("<table><tr><th>Financial year</th><td>").append(e.getFyStart()).append(" to ").append(e.getFyEnd())
                .append("</td><th>Close date</th><td>").append(e.getCloseDate()).append("</td></tr>")
                .append("<tr><th>Workpaper version</th><td>v").append(version)
                .append("</td><th>Generated</th><td>").append(Instant.now()).append("</td></tr></table>");
        h.append("<p class='small'>Generated by the Ledger Integrity &amp; Audit Intelligence Platform. ")
                .append("The platform flags risks and prepares evidence; conclusions recorded below are the ")
                .append("professional judgement of the named individuals, not of the system.</p>");

        // 2. source data & integrity (AWP-002)
        h.append("<h2>1. Source data and integrity (DAT-001/002)</h2>");
        List<ImportBatch> allBatches = batches.findByEngagementIdOrderByImportedAtDesc(id);
        h.append("<table><tr><th>Imported</th><th>File</th><th>Rows</th><th>SHA-256</th>")
                .append("<th>Added</th><th>Skipped</th><th>Balanced</th><th>TB agrees</th><th>DQ issues</th></tr>");
        for (ImportBatch b : allBatches) {
            for (ImportBatch.SourceFileRef f : b.getFiles()) {
                h.append("<tr><td>").append(b.getImportedAt()).append("</td><td>").append(esc(f.fileName()))
                        .append("</td><td class='num'>").append(f.rows())
                        .append("</td><td class='small'>").append(f.sha256())
                        .append("</td><td class='num'>").append(b.getAddedRows())
                        .append("</td><td class='num'>").append(b.getSkippedRows())
                        .append("</td><td>").append(b.isBalanced() ? "Yes" : "NO")
                        .append("</td><td>").append(b.isTbAgrees() ? "Yes" : "NO")
                        .append("</td><td class='num'>").append(b.getIssues().size()).append("</td></tr>");
            }
        }
        h.append("</table>");
        h.append("<p>Ledger population currently held: <b>")
                .append(String.format("%,d", entries.countByEngagementId(id))).append(" rows</b>.</p>");

        // 3. procedures & parameters (AWP-003)
        h.append("<h2>2. Procedures and parameters (JET-007 / AWP-003)</h2>");
        List<RuleRun> runs = ruleRuns.findByEngagementIdOrderByExecutedAtDesc(id);
        if (runs.isEmpty()) {
            h.append("<p>No rule runs executed.</p>");
        } else {
            h.append("<table><tr><th>Executed</th><th>Rule pack</th><th>Vouchers</th><th>Findings</th>")
                    .append("<th>New exceptions</th><th>Parameters (exact snapshot)</th></tr>");
            for (RuleRun r : runs) {
                h.append("<tr><td>").append(r.getExecutedAt()).append("</td><td>").append(esc(r.getPackVersion()))
                        .append("</td><td class='num'>").append(r.getPopulationVouchers())
                        .append("</td><td class='num'>").append(r.getFindings())
                        .append("</td><td class='num'>").append(r.getExceptionsCreated())
                        .append("</td><td class='small'>").append(esc(r.getParamsJson())).append("</td></tr>");
            }
            h.append("</table>");
        }

        // JET-008 / BEN-013: documented samples
        List<SampleSelection> sampleList = samples.findByEngagementIdOrderByCreatedAtDesc(id);
        if (!sampleList.isEmpty()) {
            h.append("<h2>Samples selected (JET-008 / BEN-013)</h2><table>")
                    .append("<tr><th>Method</th><th>Size</th><th>Seed</th><th>Selected by</th><th>Vouchers</th></tr>");
            for (SampleSelection s : sampleList) {
                h.append("<tr><td>").append(s.getMethod()).append("</td><td class='num'>").append(s.getSampleSize())
                        .append("</td><td>").append(s.getSeed() == null ? "n/a (risk-ranked)" : s.getSeed())
                        .append("</td><td>").append(esc(s.getSelectedBy()))
                        .append("</td><td class='small'>").append(esc(s.getVoucherIds())).append("</td></tr>");
            }
            h.append("</table><p class='small'>Random samples are seeded and reproducible; risk-ranked samples follow the current case priorities.</p>");
        }

        // ATR-007: audit-trail coverage pack
        if (tpl == null || tpl.isIncludeAuditTrail()) {
            var atr = atrAnalysis.reportOnly(id);
            if (atr.events() > 0) {
                h.append("<h2>Audit-trail coverage (ATR-002/007)</h2><table>")
                        .append("<tr><th>Events</th><td class='num'>").append(atr.events())
                        .append("</td><th>Coverage</th><td>").append(atr.firstEvent()).append(" to ").append(atr.lastEvent())
                        .append("</td></tr><tr><th>Gaps ≥ 30 days</th><td class='num'>").append(atr.gaps().size())
                        .append("</td><th>Configuration events</th><td class='num'>").append(atr.disablementEvents())
                        .append("</td></tr></table>");
                for (var g : atr.gaps()) {
                    h.append("<p class='small'>Gap: ").append(g.from()).append(" to ").append(g.to())
                            .append(" (").append(g.days()).append(" days) — coverage limitation, not proof logging was disabled.</p>");
                }
            }
        }

        // GST classification summary (both sides: purchases vs 2B, sales vs GSTR-1)
        List<GstMatchResult> gst = new java.util.ArrayList<>();
        gst.addAll(gstMatches.findBySide(id, GstMatchResult.Side.PURCHASE, true));
        gst.addAll(gstMatches.findBySide(id, GstMatchResult.Side.SALES, false));
        if (!gst.isEmpty() && (tpl == null || tpl.isIncludeGst())) {
            Map<GstMatchResult.Category, Long> gstCounts = new EnumMap<>(GstMatchResult.Category.class);
            long exposure = 0;
            for (GstMatchResult m : gst) {
                gstCounts.merge(m.getCategory(), 1L, Long::sum);
                if (m.getCategory() != GstMatchResult.Category.MATCHED) exposure += m.getTaxDiffPaise();
            }
            h.append("<h2>3. GST reconciliation — purchase register vs GSTR-2B (GS-01)</h2><table>");
            gstCounts.forEach((k, v) -> h.append("<tr><th>").append(k).append("</th><td class='num'>").append(v).append("</td></tr>"));
            h.append("<tr><th>Potential tax at stake</th><td class='num'>Rs ").append(inr(exposure)).append("</td></tr></table>")
                    .append("<p class='small'>Amounts are estimates; ITC eligibility and filing decisions remain professional judgements (GST-004).</p>");
        }

        // bank classification summary
        Map<BankMatchResult.MatchType, Long> bankCounts = new EnumMap<>(BankMatchResult.MatchType.class);
        for (BankMatchResult.MatchType t : BankMatchResult.MatchType.values()) {
            long n = bankMatches.findByEngagementIdAndMatchTypeOrderByAmountPaiseDesc(id, t).size();
            if (n > 0) bankCounts.put(t, n);
        }
        if (!bankCounts.isEmpty() && (tpl == null || tpl.isIncludeBank())) {
            h.append("<h2>4. Bank reconciliation — statement vs books (BK-01..05)</h2><table>");
            bankCounts.forEach((k, v) -> h.append("<tr><th>").append(k).append("</th><td class='num'>").append(v).append("</td></tr>"));
            h.append("</table>");
        }

        // 5. consolidated exception register (AWP-004)
        h.append("<h2>5. Investigation cases and exception register (BRD §3.3 / §17.2)</h2>");
        List<InvestigationCase> allCases = cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(id);
        List<ExceptionCase> allExceptions = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(id);
        Map<ExceptionCase.Status, Long> byStatus = new EnumMap<>(ExceptionCase.Status.class);
        allExceptions.forEach(x -> byStatus.merge(x.getStatus(), 1L, Long::sum));
        h.append("<table><tr><th>Total cases</th><td class='num'>").append(allCases.size())
                .append("</td><th>Total exceptions</th><td class='num'>").append(allExceptions.size()).append("</td></tr></table>");
        h.append("<table><tr><th>Status</th><th>Count</th></tr>");
        byStatus.forEach((k, v) -> h.append("<tr><td>").append(k).append("</td><td class='num'>").append(v).append("</td></tr>"));
        h.append("</table>");

        for (InvestigationCase c : allCases) {
            h.append("<h3>CASE-").append(String.format("%03d", c.getCaseNo())).append(" — ").append(esc(c.getTitle()))
                    .append(" [").append(c.getSeverity()).append(", priority ").append(c.getPriorityScore())
                    .append(", exposure Rs ").append(inr(c.getExposurePaise())).append("]</h3>");
            h.append("<table><tr><th>Rule</th><th>Severity</th><th>Exposure (Rs)</th><th>Reason</th>")
                    .append("<th>Source refs</th><th>Status</th><th>Decision</th></tr>");
            for (ExceptionCase x : allExceptions) {
                if (!c.getId().equals(x.getCaseId())) continue;
                h.append("<tr><td>").append(esc(x.getRuleId() + " " + x.getRuleName()))
                        .append("</td><td>").append(x.getSeverity())
                        .append("</td><td class='num'>").append(inr(x.getExposurePaise()))
                        .append("</td><td>").append(esc(x.getReason()))
                        .append("</td><td class='small'>").append(esc(x.getSourceRefs()))
                        .append("</td><td>").append(x.getStatus())
                        .append("</td><td>").append(x.getDecisionNote() == null ? "<i>pending</i>"
                                : esc(x.getDecisionNote()) + " — " + esc(x.getDecidedBy()) + ", " + x.getDecidedAt())
                        .append("</td></tr>");
            }
            h.append("</table>");
        }

        // 6. sign-off placeholder — actual sign-off is metadata on the workpaper record
        h.append("<h2>6. Sign-off</h2>")
                .append("<p class='small'>Sign-off is recorded on the platform against this workpaper version and shown in the")
                .append(" export footer. A signed version is locked; later changes create a new version (AWP-006).</p>");

        if (tpl != null && tpl.getFooterNote() != null && !tpl.getFooterNote().isBlank()) {
            h.append("<p class='small'>").append(esc(tpl.getFooterNote())).append("</p>");
        }
        h.append("</body></html>");
        return h.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String inr(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
