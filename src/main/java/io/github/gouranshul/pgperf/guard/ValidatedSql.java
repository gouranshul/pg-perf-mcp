package io.github.gouranshul.pgperf.guard;

/**
 * SQL that passed {@link SqlGuard#validate(String)}.
 *
 * @param sql            the statement with trailing semicolons and comments removed
 * @param parameterCount highest positional parameter ({@code $n}) referenced, 0 if none
 */
public record ValidatedSql(String sql, int parameterCount) {

    public boolean hasParameters() {
        return parameterCount > 0;
    }
}
