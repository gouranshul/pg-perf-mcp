package io.github.gouranshul.pgperf.guard;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Functions that must never run through the guard, even inside a read-only transaction: they
 * stall the server, touch the file system, reach other hosts, change settings, signal other
 * backends, take advisory locks, execute SQL from a string, or write sequences and statistics.
 */
final class DangerousFunctions {

    private static final Set<String> EXACT = Set.of(
            "pg_read_file", "pg_read_binary_file", "pg_stat_file", "pg_ls_dir",
            "set_config", "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf",
            "pg_rotate_logfile", "pg_switch_wal", "pg_promote", "pg_create_restore_point",
            "pg_logical_emit_message", "pg_notify", "pg_log_backend_memory_contexts",
            "pg_import_system_collations", "pg_stat_statements_reset",
            "query_to_xml", "query_to_xmlschema", "query_to_xml_and_xmlschema",
            "cursor_to_xml", "cursor_to_xmlschema",
            "nextval", "setval", "txid_current", "pg_current_xact_id");

    private record Family(String prefix, String suffix, String why) {

        boolean matches(String name) {
            return (prefix.isEmpty() || name.startsWith(prefix)) && (suffix.isEmpty() || name.endsWith(suffix));
        }
    }

    private static final List<Family> FAMILIES = List.of(
            new Family("pg_sleep", "", "it stalls the backend"),
            new Family("lo_", "", "large-object functions read and write server files"),
            new Family("dblink", "", "it connects to other servers"),
            new Family("pg_ls_", "", "it lists server directories"),
            new Family("", "_file", "it accesses server files"),
            new Family("pg_stat_reset", "", "it resets statistics"),
            new Family("pg_advisory", "", "it takes advisory locks"),
            new Family("pg_try_advisory", "", "it takes advisory locks"),
            new Family("pg_replication_", "", "it manages replication"),
            new Family("pg_create_", "", "it creates server objects"),
            new Family("pg_drop_", "", "it drops server objects"),
            new Family("pg_wal_", "", "it controls WAL replay"),
            new Family("pg_backup_", "", "it controls backups"));

    private DangerousFunctions() {
    }

    /** Returns why {@code name} (already case-folded) is blocked, or empty if it is allowed. */
    static Optional<String> reasonFor(String name) {
        if (EXACT.contains(name)) {
            return Optional.of("it can change server state or execute arbitrary SQL");
        }
        return FAMILIES.stream().filter(f -> f.matches(name)).map(Family::why).findFirst();
    }
}
