package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Lock contention: who is waiting on whom right now. */
@Component
public class LockTools {

    static final int QUERY_TEXT_LIMIT = 300;

    private final ReadOnlyExecutor executor;
    private final ToolRunner runner;

    public LockTools(ReadOnlyExecutor executor, ToolRunner runner) {
        this.executor = executor;
        this.runner = runner;
    }

    /**
     * One blocked/blocking pair.
     *
     * @param waitingSeconds        how long the blocked statement has been running (mostly waiting)
     * @param lockType              lock the blocked session is waiting for (relation, tuple, transactionid, ...)
     * @param lockMode              requested lock mode, e.g. ShareLock
     * @param relation              table involved, when the lock is on a relation
     * @param blockingState         state of the blocker, e.g. "idle in transaction"
     * @param blockingXactSeconds   age of the blocker's transaction
     */
    public record BlockingPair(int blockedPid, String blockedUser, String blockedQuery, double waitingSeconds,
            String lockType, String lockMode, String relation, int blockingPid, String blockingUser,
            String blockingState, String blockingQuery, Double blockingXactSeconds) {
    }

    /**
     * @param rootBlockers pids that block others without being blocked themselves: fix these first
     */
    public record BlockingReport(int blockedSessions, List<Integer> rootBlockers, List<BlockingPair> pairs,
            String hint) {
    }

    @McpTool(name = "blocking_sessions", title = "Blocking sessions",
            description = """
                    Show current lock contention in this database: each blocked session with the session \
                    blocking it (via pg_blocking_pids), both query texts (truncated), how long the blocked one \
                    has waited, the lock type/mode and table, and the blocker's state and transaction age. \
                    rootBlockers lists sessions at the head of a wait chain. A blocker that is "idle in \
                    transaction" usually means an application forgot to commit. This tool never cancels or \
                    terminates anything. Read-only.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public CallToolResult blockingSessions() {
        return runner.run("blocking_sessions", this::report);
    }

    BlockingReport report() {
        return executor.run(db -> {
            List<BlockingPair> pairs = db.client().sql("""
                    SELECT blocked.pid AS blocked_pid,
                           blocked.usename AS blocked_user,
                           left(blocked.query, ?) AS blocked_query,
                           extract(epoch FROM clock_timestamp() - blocked.query_start)::float8 AS waiting_seconds,
                           w.locktype, w.mode, w.relation,
                           blocking.pid AS blocking_pid,
                           blocking.usename AS blocking_user,
                           blocking.state AS blocking_state,
                           left(blocking.query, ?) AS blocking_query,
                           extract(epoch FROM clock_timestamp() - blocking.xact_start)::float8 AS blocking_xact_seconds
                    FROM pg_stat_activity blocked
                    CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(pid)
                    JOIN pg_stat_activity blocking ON blocking.pid = b.pid
                    LEFT JOIN LATERAL (
                        SELECT l.locktype, l.mode, l.relation::regclass::text AS relation
                        FROM pg_locks l
                        WHERE l.pid = blocked.pid AND NOT l.granted
                        LIMIT 1
                    ) w ON true
                    WHERE blocked.datname = current_database()
                    ORDER BY waiting_seconds DESC NULLS LAST, blocked.pid, blocking.pid
                    """)
                    .params(QUERY_TEXT_LIMIT, QUERY_TEXT_LIMIT)
                    .query((rs, n) -> new BlockingPair(
                            rs.getInt("blocked_pid"),
                            rs.getString("blocked_user"),
                            rs.getString("blocked_query"),
                            Math.round(rs.getDouble("waiting_seconds") * 10) / 10.0,
                            rs.getString("locktype"),
                            rs.getString("mode"),
                            rs.getString("relation"),
                            rs.getInt("blocking_pid"),
                            rs.getString("blocking_user"),
                            rs.getString("blocking_state"),
                            rs.getString("blocking_query"),
                            rs.getObject("blocking_xact_seconds") == null ? null
                                    : Math.round(rs.getDouble("blocking_xact_seconds") * 10) / 10.0))
                    .list();
            return summarize(pairs);
        });
    }

    static BlockingReport summarize(List<BlockingPair> pairs) {
        Set<Integer> blocked = new LinkedHashSet<>();
        Set<Integer> blockers = new LinkedHashSet<>();
        for (BlockingPair p : pairs) {
            blocked.add(p.blockedPid());
            blockers.add(p.blockingPid());
        }
        List<Integer> roots = blockers.stream().filter(pid -> !blocked.contains(pid)).toList();
        String hint = pairs.isEmpty()
                ? "No lock waits right now."
                : "Resolve the root blockers first: finish or roll back their transactions in the application. "
                        + "As a last resort a DBA can run SELECT pg_cancel_backend(pid) or pg_terminate_backend(pid);"
                        + " this server will not do it.";
        return new BlockingReport(blocked.size(), roots, pairs, hint);
    }
}
