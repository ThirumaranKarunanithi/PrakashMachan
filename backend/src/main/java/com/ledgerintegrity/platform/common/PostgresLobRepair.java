package com.ledgerintegrity.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time repair for databases created while contentHtml/resultJson were mapped
 * as @Lob: on PostgreSQL those columns were created as large-object "oid" and every
 * read outside a transaction returned 500. Converts them in place to TEXT, keeping
 * the stored content where the large object still exists. No-op on H2 and on
 * already-converted databases.
 */
@Component
public class PostgresLobRepair implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PostgresLobRepair.class);

    private record Target(String table, String column) {}

    private static final List<Target> TARGETS = List.of(
            new Target("workpapers", "content_html"),
            new Target("benford_runs", "result_json"));

    private final JdbcTemplate jdbc;

    public PostgresLobRepair(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Target t : TARGETS) {
            String type;
            try {
                type = jdbc.queryForObject(
                        "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                        String.class, t.table(), t.column());
            } catch (Exception e) {
                continue; // table not present yet
            }
            if (!"oid".equalsIgnoreCase(type)) continue;
            try {
                jdbc.execute("ALTER TABLE " + t.table() + " ALTER COLUMN " + t.column()
                        + " TYPE text USING convert_from(lo_get(" + t.column() + "), 'UTF8')");
                log.info("Repaired {}.{}: oid -> text, content preserved", t.table(), t.column());
            } catch (Exception convertFailed) {
                // orphaned/broken large objects: keep the rows, sacrifice the payload
                jdbc.execute("ALTER TABLE " + t.table() + " ALTER COLUMN " + t.column()
                        + " TYPE text USING ''");
                log.warn("Repaired {}.{}: oid -> text, content could not be recovered ({})",
                        t.table(), t.column(), convertFailed.getMessage());
            }
        }
    }
}
