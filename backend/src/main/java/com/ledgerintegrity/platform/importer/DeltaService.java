package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** DAT-006: delta imports — a later-period upload adds only new or changed records. */
@Service
public class DeltaService {

    public record DeltaResult(List<LedgerRow> added, int skipped, Set<String> identities) {}

    /** Content identity of a ledger row — independent of source file/row position. */
    public String rowIdentity(LedgerRow r) {
        return Checksums.sha256Hex(String.join("|",
                r.voucherId(), r.voucherType(), String.valueOf(r.txnDate()),
                r.createdAt() == null ? "" : r.createdAt().toString(),
                r.accountCode(),
                r.debit() == null ? "" : r.debit().toString(),
                r.credit() == null ? "" : r.credit().toString(),
                r.narration(),
                r.source() == null ? "" : r.source(),
                r.userId() == null ? "" : r.userId(),
                r.reversalOf() == null ? "" : r.reversalOf()));
    }

    /** Merge an upload into an engagement population without duplicating previously loaded records. */
    public DeltaResult deltaImport(Set<String> existing, List<LedgerRow> upload) {
        Set<String> identities = new HashSet<>(existing);
        List<LedgerRow> added = new ArrayList<>();
        int skipped = 0;
        for (LedgerRow r : upload) {
            String id = rowIdentity(r);
            if (!identities.add(id)) { skipped++; continue; }
            added.add(r);
        }
        return new DeltaResult(added, skipped, identities);
    }
}
