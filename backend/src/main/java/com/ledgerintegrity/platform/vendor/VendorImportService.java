package com.ledgerintegrity.platform.vendor;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.importer.MappingProfile;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEvent;
import com.ledgerintegrity.platform.vendor.persist.AuditTrailEventRepository;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;
import com.ledgerintegrity.platform.vendor.persist.VendorRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imports vendor master and audit-trail extracts (fixed standard CSV headers, ISO dates).
 * Delta-safe via content-identity hashes (DAT-006).
 *
 * Expected headers —
 *  vendor master: vendor_id, name, gstin, bank_account, ifsc, created_date, created_by, status
 *  audit trail:   timestamp, user_id, object, record_id, field, old_value, new_value, action
 */
@Service
public class VendorImportService {

    public record ImportOutcome(int totalRows, int added, int skipped, List<String> problems) {}

    private final VendorRecordRepository vendors;
    private final AuditTrailEventRepository auditTrail;

    public VendorImportService(VendorRecordRepository vendors, AuditTrailEventRepository auditTrail) {
        this.vendors = vendors;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public ImportOutcome importVendorMaster(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table, List.of("vendor_id", "name"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(vendors.findIdentityHashes(engagementId));
        List<VendorRecord> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String vendorId = cell(r, idx, "vendor_id");
            String name = cell(r, idx, "name");
            if (vendorId.isEmpty() || name.isEmpty()) {
                problems.add("Row " + row + ": vendor_id and name are required.");
                continue;
            }
            LocalDate created = null;
            String rawDate = cell(r, idx, "created_date");
            if (!rawDate.isEmpty()) {
                try {
                    created = LocalDate.parse(rawDate);
                } catch (DateTimeParseException e) {
                    problems.add("Row " + row + ": invalid created_date \"" + rawDate + "\".");
                }
            }
            String hash = Checksums.sha256Hex(String.join("|", vendorId, name,
                    cell(r, idx, "gstin"), cell(r, idx, "bank_account"), cell(r, idx, "ifsc"), rawDate));
            if (!existing.add(hash)) { skipped++; continue; }
            toAdd.add(new VendorRecord(engagementId, hash, vendorId, name,
                    cell(r, idx, "gstin"), cell(r, idx, "bank_account"), cell(r, idx, "ifsc"),
                    created, cell(r, idx, "created_by"), cell(r, idx, "status"), fileName, row));
        }
        vendors.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    @Transactional
    public ImportOutcome importAuditTrail(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table,
                List.of("timestamp", "user_id", "object", "record_id", "field", "action"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(auditTrail.findIdentityHashes(engagementId));
        List<AuditTrailEvent> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            String rawTs = cell(r, idx, "timestamp");
            LocalDateTime ts = MappingProfile.parseTimestamp(rawTs);
            if (ts == null) {
                problems.add("Row " + row + ": invalid timestamp \"" + rawTs + "\".");
                continue;
            }
            String hash = Checksums.sha256Hex(String.join("|", rawTs, cell(r, idx, "user_id"),
                    cell(r, idx, "object"), cell(r, idx, "record_id"), cell(r, idx, "field"),
                    cell(r, idx, "old_value"), cell(r, idx, "new_value"), cell(r, idx, "action")));
            if (!existing.add(hash)) { skipped++; continue; }
            toAdd.add(new AuditTrailEvent(engagementId, hash, ts, cell(r, idx, "user_id"),
                    cell(r, idx, "object"), cell(r, idx, "record_id"), cell(r, idx, "field"),
                    cell(r, idx, "old_value"), cell(r, idx, "new_value"), cell(r, idx, "action"),
                    fileName, row));
        }
        auditTrail.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
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
}
