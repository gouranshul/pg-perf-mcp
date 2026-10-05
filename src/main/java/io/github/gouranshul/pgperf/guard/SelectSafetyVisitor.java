package io.github.gouranshul.pgperf.guard;

import java.util.List;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Walks the whole JSqlParser AST (every CTE, subquery, join and expression, via
 * {@link TablesNamesFinder}) and rejects anything that is not a pure read: data-modifying CTEs,
 * {@code SELECT ... INTO} and row-locking clauses at any nesting depth.
 */
final class SelectSafetyVisitor extends TablesNamesFinder<Void> {

    void check(Select select) {
        try {
            getTables((net.sf.jsqlparser.statement.Statement) select);
        } catch (SqlRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Fail closed: if the AST cannot be fully walked, the query cannot be trusted.
            throw new SqlRejectedException("SQL uses a construct the guard cannot verify");
        }
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
        List<?> into = plainSelect.getIntoTables();
        if (into != null && !into.isEmpty()) {
            throw new SqlRejectedException("SELECT ... INTO creates a table and is not allowed");
        }
        rejectLocking(plainSelect);
        return super.visit(plainSelect, context);
    }

    @Override
    public <S> Void visit(SetOperationList list, S context) {
        rejectLocking(list);
        return super.visit(list, context);
    }

    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
        rejectLocking(select);
        return super.visit(select, context);
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
        // WithItem.getInsert()/getUpdate()/getDelete() cast unchecked, so test the payload type directly.
        if (!(withItem.getParenthesedStatement() instanceof ParenthesedSelect)) {
            throw dataModifyingCte(withItem.getAliasName());
        }
        return super.visit(withItem, context);
    }

    @Override
    public <S> Void visit(Insert insert, S context) {
        throw dataModifyingCte(null);
    }

    @Override
    public <S> Void visit(Update update, S context) {
        throw dataModifyingCte(null);
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        throw dataModifyingCte(null);
    }

    @Override
    public <S> Void visit(Merge merge, S context) {
        throw dataModifyingCte(null);
    }

    private static void rejectLocking(Select select) {
        if (select.getForMode() != null || select.getForUpdateTable() != null) {
            throw new SqlRejectedException("Row-locking clauses (FOR UPDATE / FOR SHARE) are not allowed");
        }
    }

    private static SqlRejectedException dataModifyingCte(String name) {
        return new SqlRejectedException("Data-modifying CTE " + (name == null ? "" : "'" + name + "' ")
                + "is not allowed: every part of a WITH query must be a SELECT");
    }
}
