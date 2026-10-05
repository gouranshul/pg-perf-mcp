package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.config.PgPerfProperties;
import io.github.gouranshul.pgperf.guard.SqlRejectedException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
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
 */
@Component
public class ToolRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolRunner.class);

    private final JsonMapper json;
    private final PgPerfProperties properties;

    public ToolRunner(JsonMapper json, PgPerfProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    public CallToolResult run(String tool, Supplier<?> body) {
        try {
            Object result = body.get();
            return CallToolResult.builder().addTextContent(json.writeValueAsString(result)).build();
        } catch (SqlRejectedException e) {
            return error("Rejected by the SQL guard: " + e.reason());
        } catch (ToolException e) {
            return error(e.getMessage());
        } catch (DataAccessException e) {
            log.warn("Tool {} failed with a database error", tool, e);
            return error(DatabaseErrors.describe(e, properties.query().statementTimeout()));
        } catch (RuntimeException e) {
            String reference = UUID.randomUUID().toString();
            log.error("Tool {} failed unexpectedly (reference {})", tool, reference, e);
            return error("Internal error in " + tool + ". Reference: " + reference);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
