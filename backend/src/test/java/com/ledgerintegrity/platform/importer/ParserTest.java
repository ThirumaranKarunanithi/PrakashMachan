package com.ledgerintegrity.platform.importer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parseAmountPaiseHandlesRupeesSeparatorsNegativesGarbage() {
        assertEquals(123456L, MappingProfile.parseAmountPaise("1234.56"));
        assertEquals(12345670L, MappingProfile.parseAmountPaise("1,23,456.7"));
        assertEquals(-50000L, MappingProfile.parseAmountPaise("-500"));
        assertNull(MappingProfile.parseAmountPaise(""));
        assertThrows(NumberFormatException.class, () -> MappingProfile.parseAmountPaise("12.3.4"));
        assertThrows(NumberFormatException.class, () -> MappingProfile.parseAmountPaise("abc"));
    }

    @Test
    void parseDateValidatesRealCalendarDatesPerFormat() {
        MappingProfile iso = profileWith(MappingProfile.DateFormat.ISO);
        MappingProfile dmyDash = profileWith(MappingProfile.DateFormat.DMY_DASH);
        MappingProfile dmySlash = profileWith(MappingProfile.DateFormat.DMY_SLASH);

        assertEquals(LocalDate.of(2025, 3, 31), iso.parseDate("2025-03-31"));
        assertEquals(LocalDate.of(2025, 3, 31), dmyDash.parseDate("31-03-2025"));
        assertEquals(LocalDate.of(2025, 3, 31), dmySlash.parseDate("31/03/2025"));
        assertNull(iso.parseDate("2025-02-30")); // not a real date
        assertNull(iso.parseDate("2025-13-01"));
        assertNull(iso.parseDate("31-03-2025")); // wrong format
    }

    @Test
    void parseTimestampAcceptsSpaceOrTAndRejectsBadClockValues() {
        assertEquals(LocalDateTime.of(2025, 4, 4, 22, 15), MappingProfile.parseTimestamp("2025-04-04 22:15"));
        assertEquals(LocalDateTime.of(2025, 4, 4, 22, 15, 33), MappingProfile.parseTimestamp("2025-04-04T22:15:33"));
        assertNull(MappingProfile.parseTimestamp("2025-04-04 25:00"));
    }

    @Test
    void checkAgainstHeaderReportsUnmappedAndMissingColumns() {
        MappingProfile p = TestData.clientAProfile();
        List<String> problems = p.checkAgainstHeader(List.of("voucher_id", "txn_date"));
        assertTrue(problems.stream().anyMatch(s -> s.contains("account_code")));
    }

    private static MappingProfile profileWith(MappingProfile.DateFormat format) {
        return new MappingProfile("t", MappingProfile.SourceType.CSV, null, format, Map.of());
    }
}
