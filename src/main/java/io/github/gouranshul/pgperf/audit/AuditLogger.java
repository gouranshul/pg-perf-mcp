package io.github.gouranshul.pgperf.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one JSON line per tool call to the {@code pgperf.audit} logger.
 *
 * <p>SQL arguments are never logged verbatim: they are replaced by a SHA-256 hash (to correlate
 * repeated calls) and the first 80 characters (to make the log readable).
 */
@Component
public class AuditLogger {

    static final String LOGGER_NAME = "pgperf.audit";
    static final int SQL_PREVIEW_LENGTH = 80;

    private static final Logger audit = LoggerFactory.getLogger(LOGGER_NAME);

    private final JsonMapper json;

    public AuditLogger(JsonMapper json) {
        this.json = json;
    }

    public enum Outcome { OK, REJECTED, ERROR }

    /**
     * @param requestId correlation id, also returned to the client on internal errors
     * @param args      raw tool arguments; values named "sql" are hashed before logging
     * @param rowCount  rows returned, null when not applicable
     * @param reason    rejection or error message shown to the client, null on success
     */
    public void record(String requestId, String tool, Map<String, Object> args, long durationMs, Integer rowCount,
            Outcome outcome, String reason) {
        audit.info(toJson(Instant.now(), requestId, tool, args, durationMs, rowCount, outcome, reason));
    }

    String toJson(Instant timestamp, String requestId, String tool, Map<String, Object> args, long durationMs,
            Integer rowCount, Outcome outcome, String reason) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", timestamp.toString());
        line.put("event", "mcp_tool_call");
        line.put("requestId", requestId);
        line.put("tool", tool);
        line.put("args", redact(args));
        line.put("durationMs", durationMs);
        line.put("rowCount", rowCount);
        line.put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
        line.put("reason", reason);
        return json.writeValueAsString(line);
    }

    static Map<String, Object> redact(Map<String, Object> args) {
        Map<String, Object> safe = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if ("sql".equals(key) && value instanceof String sql) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("sha256", sha256(sql));
                summary.put("preview", sql.length() > SQL_PREVIEW_LENGTH ? sql.substring(0, SQL_PREVIEW_LENGTH) : sql);
                summary.put("length", sql.length());
                safe.put(key, summary);
            } else {
                safe.put(key, value);
            }
        });
        return safe;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
