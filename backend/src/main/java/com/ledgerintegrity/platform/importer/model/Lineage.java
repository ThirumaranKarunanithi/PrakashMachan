package com.ledgerintegrity.platform.importer.model;

/**
 * DAT-005: every normalised record traces to its original source row.
 *
 * @param file source file name
 * @param row  1-based line number in the source file (header = line 1)
 */
public record Lineage(String file, int row) {
    @Override
    public String toString() {
        return file + ":" + row;
    }
}
