package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.Lineage;
import com.ledgerintegrity.platform.importer.model.QualityIssue;
import com.ledgerintegrity.platform.importer.model.QualityIssue.IssueType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Tally Daybook XML export (ENVELOPE > ... > TALLYMESSAGE > VOUCHER) into the
 * standard ledger model (BRD §4.1 Tally XML import).
 *
 * Tally conventions handled:
 *  - DATE is yyyyMMdd
 *  - ledger lines live in ALLLEDGERENTRIES.LIST / LEDGERENTRIES.LIST
 *  - ISDEEMEDPOSITIVE = Yes marks a debit; amounts may carry Tally's sign convention
 *    (negative = debit), so the flag wins and the absolute value is used
 *  - Tally has no account codes — the ledger name serves as both code and name
 *  - creation timestamps/users are not in a standard daybook export; those fields stay
 *    null and timestamp-dependent rules degrade gracefully
 *
 * Lineage: DOM parsing loses physical line numbers, so the "row" is the 1-based ledger
 * entry index within the file — stable across identical files, good enough to locate
 * the entry in any XML viewer.
 */
public final class TallyXmlParser {

    public record ParseResult(List<LedgerRow> rows, List<QualityIssue> issues, int voucherCount) {}

    private static final DateTimeFormatter TALLY_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TallyXmlParser() {}

    public static ParseResult parse(byte[] content, String fileName) {
        List<LedgerRow> rows = new ArrayList<>();
        List<QualityIssue> issues = new ArrayList<>();
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening — client files are untrusted
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (Exception e) {
            issues.add(new QualityIssue(IssueType.INVALID_DATE, "xml", null,
                    "File could not be parsed as Tally XML: " + e.getMessage(), new Lineage(fileName, 1)));
            return new ParseResult(rows, issues, 0);
        }

        NodeList vouchers = doc.getElementsByTagName("VOUCHER");
        int entryIndex = 0;
        for (int i = 0; i < vouchers.getLength(); i++) {
            Element voucher = (Element) vouchers.item(i);
            String voucherNo = firstText(voucher, "VOUCHERNUMBER");
            if (voucherNo.isEmpty()) voucherNo = firstText(voucher, "GUID");
            String voucherType = voucher.getAttribute("VCHTYPE");
            if (voucherType.isEmpty()) voucherType = firstText(voucher, "VOUCHERTYPENAME");
            String narration = firstText(voucher, "NARRATION");
            String rawDate = firstText(voucher, "DATE");

            Lineage voucherLineage = new Lineage(fileName, entryIndex + 1);
            if (voucherNo.isEmpty()) {
                issues.add(new QualityIssue(IssueType.MISSING_REQUIRED_FIELD, "VOUCHERNUMBER", null,
                        "Voucher #" + (i + 1) + " has no VOUCHERNUMBER or GUID — skipped.", voucherLineage));
                continue;
            }
            LocalDate txnDate;
            try {
                txnDate = LocalDate.parse(rawDate, TALLY_DATE);
            } catch (DateTimeParseException e) {
                issues.add(new QualityIssue(IssueType.INVALID_DATE, "DATE", rawDate,
                        "Voucher " + voucherNo + ": DATE \"" + rawDate + "\" is not yyyyMMdd — skipped.", voucherLineage));
                continue;
            }

            List<Element> entries = ledgerEntries(voucher);
            if (entries.isEmpty()) {
                issues.add(new QualityIssue(IssueType.NO_AMOUNT, null, null,
                        "Voucher " + voucherNo + " has no ledger entries.", voucherLineage));
                continue;
            }
            for (Element entry : entries) {
                entryIndex++;
                Lineage lineage = new Lineage(fileName, entryIndex);
                String ledgerName = firstText(entry, "LEDGERNAME");
                if (ledgerName.isEmpty()) {
                    issues.add(new QualityIssue(IssueType.MISSING_REQUIRED_FIELD, "LEDGERNAME", null,
                            "Voucher " + voucherNo + ": ledger entry without LEDGERNAME.", lineage));
                    continue;
                }
                String rawAmount = firstText(entry, "AMOUNT");
                Long paise = parsePaise(rawAmount);
                if (paise == null) {
                    issues.add(new QualityIssue(IssueType.NON_NUMERIC_AMOUNT, "AMOUNT", rawAmount,
                            "Voucher " + voucherNo + ": AMOUNT \"" + rawAmount + "\" is not a number.", lineage));
                    continue;
                }
                boolean isDebit = "Yes".equalsIgnoreCase(firstText(entry, "ISDEEMEDPOSITIVE"))
                        || (firstText(entry, "ISDEEMEDPOSITIVE").isEmpty() && paise < 0);
                long abs = Math.abs(paise);
                rows.add(new LedgerRow(voucherNo, voucherType.isEmpty() ? "Journal" : voucherType,
                        txnDate, null,
                        ledgerName, ledgerName,
                        isDebit ? abs : null, isDebit ? null : abs,
                        narration, "TallyXML", null, null, lineage));
            }
        }
        return new ParseResult(rows, issues, vouchers.getLength());
    }

    // ---------- helpers ----------

    private static List<Element> ledgerEntries(Element voucher) {
        List<Element> out = new ArrayList<>();
        for (String tag : new String[]{"ALLLEDGERENTRIES.LIST", "LEDGERENTRIES.LIST"}) {
            NodeList list = voucher.getElementsByTagName(tag);
            for (int i = 0; i < list.getLength(); i++) out.add((Element) list.item(i));
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** Text of the first descendant with the given tag, preferring a direct child. */
    private static String firstText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) return "";
        Node chosen = list.item(0);
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i).getParentNode() == parent) { chosen = list.item(i); break; }
        }
        String text = chosen.getTextContent();
        return text == null ? "" : text.trim();
    }

    private static Long parsePaise(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replace(",", "")).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
