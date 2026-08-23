package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.persist.ImportBatch;
import com.ledgerintegrity.platform.importer.persist.ImportBatchRepository;
import com.ledgerintegrity.platform.importer.persist.LedgerEntry;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.workpaper.persist.Workpaper;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Screen-7/8 deliverables: source-row context (every finding opens back into the
 * rows around it in the original file), Excel exception register, workpaper PDF,
 * and the Audit File Pack — one zip holding the reviewable evidence set.
 */
@RestController
public class ExportController {

    private final TenantGuard guard;
    private final LedgerEntryRepository entries;
    private final ExceptionCaseRepository exceptions;
    private final WorkpaperRepository workpapers;
    private final ImportBatchRepository batches;
    private final com.ledgerintegrity.platform.gst.GstReconciliationService gst;
    private final com.ledgerintegrity.platform.rules.persist.RiskWeightConfigRepository weights;

    public ExportController(TenantGuard guard, LedgerEntryRepository entries,
                            ExceptionCaseRepository exceptions, WorkpaperRepository workpapers,
                            ImportBatchRepository batches,
                            com.ledgerintegrity.platform.gst.GstReconciliationService gst,
                            com.ledgerintegrity.platform.rules.persist.RiskWeightConfigRepository weights) {
        this.guard = guard;
        this.entries = entries;
        this.exceptions = exceptions;
        this.workpapers = workpapers;
        this.batches = batches;
        this.gst = gst;
        this.weights = weights;
    }

    // ---------- source-row context (DAT-005 made navigable) ----------

    public record ContextRow(int sourceRow, String voucherId, String txnDate, String accountCode,
                             String accountName, Long debitPaise, Long creditPaise,
                             String narration, String userId, boolean flagged) {}

    public record SourceContext(String file, int fromRow, int toRow, List<ContextRow> rows) {}

    /** The rows AROUND a voucher in its original source file, flagged rows marked. */
    @GetMapping("/api/engagements/{id}/source-context")
    public SourceContext sourceContext(@PathVariable UUID id, @RequestParam String voucherId) {
        guard.engagement(id);
        List<LedgerEntry> own = entries.findByEngagementIdAndVoucherId(id, voucherId.trim());
        if (own.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Voucher " + voucherId + " is not in this engagement's population.");
        }
        LedgerRow first = own.get(0).toRow();
        String file = first.lineage().file();
        int min = own.stream().map(LedgerEntry::toRow)
                .filter(r -> r.lineage().file().equals(file)).mapToInt(r -> r.lineage().row()).min().orElse(0);
        int max = own.stream().map(LedgerEntry::toRow)
                .filter(r -> r.lineage().file().equals(file)).mapToInt(r -> r.lineage().row()).max().orElse(0);
        int from = Math.max(2, min - 3), to = max + 3;
        List<ContextRow> rows = new ArrayList<>();
        for (LedgerEntry e : entries.findByEngagementIdAndSourceFileAndSourceRowBetweenOrderBySourceRowAsc(
                id, file, from, to)) {
            LedgerRow r = e.toRow();
            rows.add(new ContextRow(r.lineage().row(), r.voucherId(),
                    r.txnDate() == null ? "" : r.txnDate().toString(), r.accountCode(), r.accountName(),
                    r.debit(), r.credit(), r.narration(), r.userId(),
                    voucherId.trim().equals(r.voucherId())));
        }
        return new SourceContext(file, from, to, rows);
    }

    // ---------- Excel exception register ----------

    @GetMapping("/api/engagements/{id}/exceptions.xlsx")
    public ResponseEntity<byte[]> exceptionsXlsx(@PathVariable UUID id) throws IOException {
        guard.engagement(id);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Exception register");
            String[] head = {"Rule", "Name", "Severity", "Exposure (INR)", "Status", "Vouchers",
                    "Reason", "Decision note", "Decided by", "Source refs"};
            var hr = sheet.createRow(0);
            var bold = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            for (int i = 0; i < head.length; i++) {
                var c = hr.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
            }
            int r = 1;
            for (ExceptionCase x : exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(id)) {
                var row = sheet.createRow(r++);
                row.createCell(0).setCellValue(x.getRuleId());
                row.createCell(1).setCellValue(x.getRuleName());
                row.createCell(2).setCellValue(x.getSeverity().name());
                row.createCell(3).setCellValue(x.getExposurePaise() / 100.0);
                row.createCell(4).setCellValue(x.getStatus().name());
                row.createCell(5).setCellValue(x.getVoucherIds());
                row.createCell(6).setCellValue(x.getReason());
                row.createCell(7).setCellValue(x.getDecisionNote() == null ? "" : x.getDecisionNote());
                row.createCell(8).setCellValue(x.getDecidedBy() == null ? "" : x.getDecidedBy());
                row.createCell(9).setCellValue(x.getSourceRefs());
            }
            for (int i = 0; i < head.length; i++) sheet.setColumnWidth(i, Math.min(60, 18 + i * 2) * 256);
            wb.write(out);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=exception-register.xlsx")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    // ---------- workpaper PDF ----------

    @GetMapping(value = "/api/workpapers/{id}/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> workpaperPdf(@PathVariable UUID id) throws IOException {
        Workpaper w = guard.workpaper(id);
        byte[] pdf = htmlToPdf(w.getContentHtml());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=workpaper-v" + w.getVersion() + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    static byte[] htmlToPdf(String html) throws IOException {
        // openhtmltopdf needs well-formed XHTML; jsoup normalises whatever we stored
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        doc.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new com.openhtmltopdf.pdfboxout.PdfRendererBuilder()
                    .withHtmlContent(doc.html(), null)
                    .toStream(out)
                    .run();
            return out.toByteArray();
        }
    }

    // ---------- Audit File Pack ----------

    /** One zip: workpaper (HTML + PDF), exception register, correction schedule,
     *  import manifest with checksums, methodology configuration. */
    @GetMapping(value = "/api/engagements/{id}/audit-pack.zip", produces = "application/zip")
    public ResponseEntity<byte[]> auditPack(@PathVariable UUID id) throws IOException {
        Engagement e = guard.engagement(id);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            Workpaper latest = workpapers.findByEngagementIdOrderByVersionDesc(id).stream()
                    .findFirst().orElse(null);
            if (latest != null) {
                put(zip, "workpaper-v" + latest.getVersion() + ".html",
                        latest.getContentHtml().getBytes(StandardCharsets.UTF_8));
                put(zip, "workpaper-v" + latest.getVersion() + ".pdf", htmlToPdf(latest.getContentHtml()));
            }
            put(zip, "exception-register.xlsx", exceptionsXlsx(id).getBody());
            if (e.getSubscribedModules().contains("GST")) {
                var rows = new ArrayList<List<String>>();
                for (var r : gst.correctionSchedule(id)) {
                    rows.add(List.of(r.side(), r.category(), r.reference(), r.invoiceOrPeriod(), r.party(),
                            money(r.booksTaxablePaise()), money(r.portalTaxablePaise()),
                            money(r.booksTaxPaise()), money(r.portalTaxPaise()),
                            String.format("%.2f", r.taxEffectPaise() / 100.0), r.suggestedAction()));
                }
                put(zip, "gst-correction-schedule.csv", Csv.serialize(
                        List.of("side", "category", "gstin_or_period", "invoice_or_period", "party",
                                "books_taxable_inr", "portal_taxable_inr", "books_tax_inr", "portal_tax_inr",
                                "tax_effect_inr", "suggested_action"), rows).getBytes(StandardCharsets.UTF_8));
            }
            // import manifest: every source file and its checksum (DAT-001)
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("engagement", e.getClientName());
            manifest.put("financialYear", e.getFyStart() + " to " + e.getFyEnd());
            List<Map<String, Object>> files = new ArrayList<>();
            for (ImportBatch b : batches.findByEngagementIdOrderByImportedAtDesc(id)) {
                for (ImportBatch.SourceFileRef f : b.getFiles()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("file", f.fileName());
                    m.put("bytes", f.bytes());
                    m.put("sha256", f.sha256());
                    m.put("rows", f.rows());
                    m.put("importedAt", b.getImportedAt().toString());
                    files.add(m);
                }
            }
            manifest.put("sourceFiles", files);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            put(zip, "import-manifest.json",
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            // methodology: rule pack + current weights and caps
            Map<String, Object> method = new LinkedHashMap<>();
            method.put("rulePack", com.ledgerintegrity.platform.rules.RulePack.current().version());
            weights.findTopByFirmIdOrderByVersionDesc(e.getFirmId()).ifPresent(c -> {
                method.put("severityWeights", Map.of("high", c.getHighWeight(),
                        "medium", c.getMediumWeight(), "low", c.getLowWeight()));
                method.put("familyCaps", Map.of(
                        "reconciliation", c.getReconciliationCap(), "deterministic", c.getDeterministicCap(),
                        "behaviourAccess", c.getBehaviourCap(), "statistical", c.getStatisticalCap(),
                        "relationship", c.getRelationshipCap(), "evidence", c.getEvidenceCap()));
                method.put("configVersion", c.getVersion());
            });
            put(zip, "methodology.json",
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(method));
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=audit-file-pack.zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(buf.toByteArray());
    }

    private static String money(Long paise) {
        return paise == null ? "" : String.format("%.2f", paise / 100.0);
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
