package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.gst.persist.Gstr1Invoice;
import com.ledgerintegrity.platform.gst.persist.Gstr1InvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.Gstr2bInvoice;
import com.ledgerintegrity.platform.gst.persist.Gstr2bInvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.Gstr3bSummary;
import com.ledgerintegrity.platform.gst.persist.Gstr3bSummaryRepository;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoice;
import com.ledgerintegrity.platform.gst.persist.PurchaseInvoiceRepository;
import com.ledgerintegrity.platform.gst.persist.SalesInvoice;
import com.ledgerintegrity.platform.gst.persist.SalesInvoiceRepository;
import com.ledgerintegrity.platform.importer.MappingProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imports the GST-side registers (MVP: fixed standard CSV headers, ISO dates).
 * Delta imports skip previously loaded rows via content-identity hashes (DAT-006).
 *
 * Expected headers —
 *  purchase register: invoice_no, invoice_date, vendor_id, vendor_name, gstin,
 *                     taxable_value, tax_amount, total, voucher_id
 *  GSTR-2B:           supplier_gstin, supplier_name, invoice_no, invoice_date,
 *                     taxable_value, tax_amount, filing_status
 */
@Service
public class GstImportService {

    public record ImportOutcome(int totalRows, int added, int skipped, List<String> problems) {}

    private final PurchaseInvoiceRepository purchases;
    private final Gstr2bInvoiceRepository g2b;
    private final SalesInvoiceRepository sales;
    private final Gstr1InvoiceRepository g1;
    private final Gstr3bSummaryRepository g3b;

    public GstImportService(PurchaseInvoiceRepository purchases, Gstr2bInvoiceRepository g2b,
                            SalesInvoiceRepository sales, Gstr1InvoiceRepository g1,
                            Gstr3bSummaryRepository g3b) {
        this.purchases = purchases;
        this.g2b = g2b;
        this.sales = sales;
        this.g1 = g1;
        this.g3b = g3b;
    }

    @Transactional
    public ImportOutcome importPurchaseRegister(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table,
                List.of("invoice_no", "invoice_date", "vendor_name", "gstin", "taxable_value", "tax_amount"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(purchases.findIdentityHashes(engagementId));
        List<PurchaseInvoice> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String invoiceNo = cell(r, idx, "invoice_no");
            LocalDate date = date(cell(r, idx, "invoice_date"), row, problems);
            if (invoiceNo.isEmpty() || date == null) continue; // problem recorded
            long taxable = money(cell(r, idx, "taxable_value"), row, "taxable_value", problems);
            long tax = money(cell(r, idx, "tax_amount"), row, "tax_amount", problems);
            long total = idx.containsKey("total") ? money(cell(r, idx, "total"), row, "total", problems) : taxable + tax;
            String gstin = cell(r, idx, "gstin");
            String hash = Checksums.sha256Hex(String.join("|", gstin, invoiceNo, date.toString(),
                    String.valueOf(taxable), String.valueOf(tax)));
            if (!existing.add(hash)) { skipped++; continue; }
            PurchaseInvoice pi = new PurchaseInvoice(engagementId, hash, invoiceNo, date,
                    cell(r, idx, "vendor_id"), cell(r, idx, "vendor_name"), gstin,
                    taxable, tax, total, cell(r, idx, "voucher_id"), fileName, row);
            String ownG = cell(r, idx, "own_gstin");
            if (!ownG.isEmpty()) pi.setOwnGstin(ownG);
            toAdd.add(pi);
        }
        purchases.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    @Transactional
    public ImportOutcome importGstr2b(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table,
                List.of("supplier_gstin", "supplier_name", "invoice_no", "invoice_date", "taxable_value", "tax_amount"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(g2b.findIdentityHashes(engagementId));
        List<Gstr2bInvoice> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String invoiceNo = cell(r, idx, "invoice_no");
            LocalDate date = date(cell(r, idx, "invoice_date"), row, problems);
            if (invoiceNo.isEmpty() || date == null) continue;
            long taxable = money(cell(r, idx, "taxable_value"), row, "taxable_value", problems);
            long tax = money(cell(r, idx, "tax_amount"), row, "tax_amount", problems);
            String gstin = cell(r, idx, "supplier_gstin");
            String hash = Checksums.sha256Hex(String.join("|", gstin, invoiceNo, date.toString(),
                    String.valueOf(taxable), String.valueOf(tax)));
            if (!existing.add(hash)) { skipped++; continue; }
            toAdd.add(new Gstr2bInvoice(engagementId, hash, gstin, cell(r, idx, "supplier_name"),
                    invoiceNo, date, taxable, tax, cell(r, idx, "filing_status"), fileName, row));
        }
        g2b.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    /** Sales register: invoice_no, invoice_date, customer_id, customer_name, gstin, taxable_value, tax_amount, total, voucher_id */
    @Transactional
    public ImportOutcome importSalesRegister(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table,
                List.of("invoice_no", "invoice_date", "customer_name", "gstin", "taxable_value", "tax_amount"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(sales.findIdentityHashes(engagementId));
        List<SalesInvoice> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String invoiceNo = cell(r, idx, "invoice_no");
            LocalDate date = date(cell(r, idx, "invoice_date"), row, problems);
            if (invoiceNo.isEmpty() || date == null) continue;
            long taxable = money(cell(r, idx, "taxable_value"), row, "taxable_value", problems);
            long tax = money(cell(r, idx, "tax_amount"), row, "tax_amount", problems);
            long total = idx.containsKey("total") ? money(cell(r, idx, "total"), row, "total", problems) : taxable + tax;
            String gstin = cell(r, idx, "gstin");
            String hash = Checksums.sha256Hex(String.join("|", "SALES", gstin, invoiceNo, date.toString(),
                    String.valueOf(taxable), String.valueOf(tax)));
            if (!existing.add(hash)) { skipped++; continue; }
            SalesInvoice si = new SalesInvoice(engagementId, hash, invoiceNo, date,
                    cell(r, idx, "customer_id"), cell(r, idx, "customer_name"), gstin,
                    taxable, tax, total, cell(r, idx, "voucher_id"), fileName, row);
            String ownGs = cell(r, idx, "own_gstin");
            if (!ownGs.isEmpty()) si.setOwnGstin(ownGs);
            toAdd.add(si);
        }
        sales.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    /** GSTR-1: customer_gstin, customer_name, invoice_no, invoice_date, taxable_value, tax_amount, filing_status */
    @Transactional
    public ImportOutcome importGstr1(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table,
                List.of("customer_gstin", "customer_name", "invoice_no", "invoice_date", "taxable_value", "tax_amount"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(g1.findIdentityHashes(engagementId));
        List<Gstr1Invoice> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String invoiceNo = cell(r, idx, "invoice_no");
            LocalDate date = date(cell(r, idx, "invoice_date"), row, problems);
            if (invoiceNo.isEmpty() || date == null) continue;
            long taxable = money(cell(r, idx, "taxable_value"), row, "taxable_value", problems);
            long tax = money(cell(r, idx, "tax_amount"), row, "tax_amount", problems);
            String gstin = cell(r, idx, "customer_gstin");
            String hash = Checksums.sha256Hex(String.join("|", "G1", gstin, invoiceNo, date.toString(),
                    String.valueOf(taxable), String.valueOf(tax)));
            if (!existing.add(hash)) { skipped++; continue; }
            toAdd.add(new Gstr1Invoice(engagementId, hash, gstin, cell(r, idx, "customer_name"),
                    invoiceNo, date, taxable, tax, cell(r, idx, "filing_status"), fileName, row));
        }
        g1.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    /** GSTR-3B summary: period (YYYY-MM), taxable_value, tax_amount. Re-import replaces a period's amounts. */
    @Transactional
    public ImportOutcome importGstr3b(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table, List.of("period", "taxable_value", "tax_amount"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        int added = 0, skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String period = cell(r, idx, "period");
            if (!period.matches("\\d{4}-\\d{2}")) {
                problems.add("Row " + row + ": period \"" + period + "\" is not YYYY-MM.");
                continue;
            }
            long taxable = money(cell(r, idx, "taxable_value"), row, "taxable_value", problems);
            long tax = money(cell(r, idx, "tax_amount"), row, "tax_amount", problems);
            var found = g3b.findByEngagementIdAndPeriod(engagementId, period);
            if (found.isPresent()) {
                if (found.get().getTaxablePaise() == taxable && found.get().getTaxPaise() == tax) { skipped++; continue; }
                found.get().setAmounts(taxable, tax);
                g3b.save(found.get());
                added++;
            } else {
                g3b.save(new Gstr3bSummary(engagementId, period, taxable, tax, fileName, row));
                added++;
            }
        }
        return new ImportOutcome(table.rows().size(), added, skipped, problems);
    }

    // ---------- helpers ----------

    private static List<String> checkHeader(Csv.Table table, List<String> required) {
        List<String> problems = new ArrayList<>();
        for (String col : required) {
            if (!table.header().contains(col)) problems.add("Missing required column \"" + col + "\".");
        }
        return problems;
    }

    private static Map<String, Integer> index(Csv.Table table) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < table.header().size(); i++) idx.put(table.header().get(i), i);
        return idx;
    }

    private static String cell(List<String> r, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        return (i == null || i >= r.size()) ? "" : r.get(i).trim();
    }

    private static LocalDate date(String v, int row, List<String> problems) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            problems.add("Row " + row + ": invalid date \"" + v + "\".");
            return null;
        }
    }

    private static long money(String v, int row, String col, List<String> problems) {
        try {
            Long p = MappingProfile.parseAmountPaise(v);
            return p == null ? 0L : p;
        } catch (NumberFormatException e) {
            problems.add("Row " + row + ": non-numeric " + col + " \"" + v + "\".");
            return 0L;
        }
    }
}
