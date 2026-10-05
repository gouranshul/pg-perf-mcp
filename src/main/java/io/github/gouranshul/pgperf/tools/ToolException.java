package io.github.gouranshul.pgperf.tools;

/**
 * An expected, user-facing failure (bad argument, missing extension, ...). The message is sent to
 * the MCP client as-is, so it must be helpful and must never contain internal details.
 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
