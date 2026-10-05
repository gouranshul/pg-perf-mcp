package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.audit.AuditLogger;
import io.github.gouranshul.pgperf.audit.AuditLogger.Outcome;
import io.github.gouranshul.pgperf.audit.RowCounted;
import io.github.gouranshul.pgperf.config.PgPerfProperties;
import io.github.gouranshul.pgperf.db.DatabaseErrors;
import io.github.gouranshul.pgperf.guard.SqlRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one tool call and turns its outcome into a {@link CallToolResult}: JSON on success, a safe
 * and actionable message on failure. Spring AI's default error result would append the exception
 * root cause, which can leak connection details; every tool therefore goes through this class.
 *
 * <p>Each call is also observed (timer {@code mcp.tool.duration} tagged by tool and outcome, plus a
 * trace span when tracing is enabled), counted in {@code mcp.guard.rejections} when the SQL guard
 * refuses it, and written to the audit log.
 */
@Component
public class ToolRunner {

    static final String DURATION_METRIC = "mcp.tool.duration";
    static final String REJECTIONS_METRIC = "mcp.guard.rejections";

    private static final Logger log = LoggerFactory.getLogger(ToolRunner.class);

    private final JsonMapper json;
    private final PgPerfProperties properties;
    private final AuditLogger audit;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public ToolRunner(JsonMapper json, PgPerfProperties properties, AuditLogger audit,
            ObservationRegistry observations, MeterRegistry meters) {
        this.json = json;
        this.properties = properties;
        this.audit = audit;
        this.observations = observations;
        this.meters = meters;
    }

    /** Argument map that, unlike {@link Map#of}, accepts null values (omitted optional parameters). */
    public static Map<String, Object> args(Object... keysAndValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            args.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return args;
    }

    public CallToolResult run(String tool, Map<String, Object> args, Supplier<?> body) {
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        Observation observation = Observation.createNotStarted(DURATION_METRIC, observations)
                .contextualName("mcp.tool " + tool)
                .lowCardinalityKeyValue("tool", tool)
                .start();

        CallToolResult result;
        Outcome outcome;
        String reason = null;
        Integer rows = null;
        try (Observation.Scope ignored = observation.openScope()) {
            Object value = body.get();
            rows = value instanceof RowCounted counted ? counted.rowCount() : null;
            result = CallToolResult.builder().addTextContent(json.writeValueAsString(value)).build();
            outcome = Outcome.OK;
        } catch (SqlRejectedException e) {
            meters.counter(REJECTIONS_METRIC, "tool", tool).increment();
            reason = "Rejected by the SQL guard: " + e.reason();
            outcome = Outcome.REJECTED;
            result = error(reason);
        } catch (ToolException e) {
            reason = e.getMessage();
            outcome = Outcome.ERROR;
            result = error(reason);
        } catch (DataAccessException e) {
            log.warn("Tool {} failed with a database error (request {})", tool, requestId, e);
            reason = DatabaseErrors.describe(e, properties.query().statementTimeout());
            outcome = Outcome.ERROR;
            result = error(reason);
            observation.error(e);
        } catch (RuntimeException e) {
            log.error("Tool {} failed unexpectedly (request {})", tool, requestId, e);
            reason = "Internal error in " + tool + ". Reference: " + requestId;
            outcome = Outcome.ERROR;
            result = error(reason);
            observation.error(e);
        }

        observation.lowCardinalityKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT)).stop();
        audit.record(requestId, tool, args, (System.nanoTime() - start) / 1_000_000, rows, outcome, reason);
        return result;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
