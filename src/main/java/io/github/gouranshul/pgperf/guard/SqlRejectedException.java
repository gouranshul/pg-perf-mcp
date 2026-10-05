package io.github.gouranshul.pgperf.guard;

/**
 * Thrown when SQL fails validation. The message is safe to show to the MCP client and explains
 * why the statement was refused.
 */
public class SqlRejectedException extends RuntimeException {

    public SqlRejectedException(String reason) {
        super(reason);
    }

    public String reason() {
        return getMessage();
    }
}
