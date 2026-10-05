package io.github.gouranshul.pgperf.audit;

/** Implemented by tool results so the audit log can record how many rows a call returned. */
public interface RowCounted {

    int rowCount();
}
