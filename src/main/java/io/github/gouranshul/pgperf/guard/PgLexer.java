package io.github.gouranshul.pgperf.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal tokenizer that follows PostgreSQL's own lexical rules (with
 * {@code standard_conforming_strings = on}, which the executor enforces per call).
 *
 * <p>It exists so the guard does not depend only on JSqlParser's reading of the text. Where the two
 * lexers could disagree (nested block comments, backslashes in strings, dollar quoting), this
 * class decides how Postgres will actually split statements and which identifiers are function
 * calls. Anything it cannot tokenize is rejected.
 */
final class PgLexer {

    enum Type {
        IDENTIFIER,
        QUOTED_IDENTIFIER,
        STRING,
        NUMBER,
        PARAMETER,
        SEMICOLON,
        LEFT_PAREN,
        RIGHT_PAREN,
        OTHER
    }

    /**
     * One token. For identifiers {@code value} is the name as Postgres resolves it: unquoted names
     * are folded to lower case, quoted names keep their case.
     */
    record Token(Type type, String value, int start, int end) {

        boolean isIdentifier() {
            return type == Type.IDENTIFIER || type == Type.QUOTED_IDENTIFIER;
        }
    }

    private final String sql;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;

    private PgLexer(String sql) {
        this.sql = sql;
    }

    static List<Token> tokenize(String sql) {
        PgLexer lexer = new PgLexer(sql);
        lexer.run();
        return List.copyOf(lexer.tokens);
    }

    private void run() {
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (startsWith("--")) {
                skipLineComment();
            } else if (startsWith("/*")) {
                skipBlockComment();
            } else if (isPrefixedString('e')) {
                readQuoted(pos, pos + 1, '\'', true, Type.STRING);
            } else if (isPrefixedString('b') || isPrefixedString('x') || isPrefixedString('n')) {
                readQuoted(pos, pos + 1, '\'', false, Type.STRING);
            } else if (startsWithIgnoreCase("u&'")) {
                readQuoted(pos, pos + 2, '\'', false, Type.STRING);
            } else if (startsWithIgnoreCase("u&\"")) {
                readQuoted(pos, pos + 2, '"', false, Type.QUOTED_IDENTIFIER);
            } else if (c == '\'') {
                readQuoted(pos, pos, '\'', false, Type.STRING);
            } else if (c == '"') {
                readQuoted(pos, pos, '"', false, Type.QUOTED_IDENTIFIER);
            } else if (c == '$') {
                readDollar();
            } else if (isIdentifierStart(c)) {
                readIdentifier();
            } else if (Character.isDigit(c) || (c == '.' && pos + 1 < sql.length() && Character.isDigit(sql.charAt(pos + 1)))) {
                readNumber();
            } else {
                Type type = switch (c) {
                    case ';' -> Type.SEMICOLON;
                    case '(' -> Type.LEFT_PAREN;
                    case ')' -> Type.RIGHT_PAREN;
                    default -> Type.OTHER;
                };
                tokens.add(new Token(type, String.valueOf(c), pos, pos + 1));
                pos++;
            }
        }
    }

    private void skipLineComment() {
        int newline = sql.indexOf('\n', pos);
        pos = newline < 0 ? sql.length() : newline + 1;
    }

    /** Postgres block comments nest, unlike most SQL dialects. */
    private void skipBlockComment() {
        int depth = 0;
        while (pos < sql.length()) {
            if (startsWith("/*")) {
                depth++;
                pos += 2;
            } else if (startsWith("*/")) {
                depth--;
                pos += 2;
                if (depth == 0) {
                    return;
                }
            } else {
                pos++;
            }
        }
        throw new SqlRejectedException("SQL contains an unterminated /* comment");
    }

    /**
     * Reads a quoted token. The quote character is escaped by doubling it. In E'' strings a
     * backslash also escapes the next character; in every other string it is a literal character.
     */
    private void readQuoted(int tokenStart, int quotePos, char quote, boolean backslashEscapes, Type type) {
        int i = quotePos + 1;
        StringBuilder value = new StringBuilder();
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < sql.length()) {
                value.append(sql.charAt(i + 1));
                i += 2;
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    value.append(quote);
                    i += 2;
                } else {
                    tokens.add(new Token(type, value.toString(), tokenStart, i + 1));
                    pos = i + 1;
                    return;
                }
            } else {
                value.append(c);
                i++;
            }
        }
        throw new SqlRejectedException("SQL contains an unterminated "
                + (quote == '"' ? "quoted identifier" : "string literal"));
    }

    /** {@code $1} is a positional parameter; {@code $tag$...$tag$} is a dollar-quoted string. */
    private void readDollar() {
        int start = pos;
        if (pos + 1 < sql.length() && Character.isDigit(sql.charAt(pos + 1))) {
            int i = pos + 1;
            while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                i++;
            }
            tokens.add(new Token(Type.PARAMETER, sql.substring(pos + 1, i), start, i));
            pos = i;
            return;
        }
        int i = pos + 1;
        while (i < sql.length() && isIdentifierPart(sql.charAt(i)) && sql.charAt(i) != '$') {
            i++;
        }
        if (i < sql.length() && sql.charAt(i) == '$') {
            String delimiter = sql.substring(pos, i + 1);
            int close = sql.indexOf(delimiter, i + 1);
            if (close < 0) {
                throw new SqlRejectedException("SQL contains an unterminated dollar-quoted string");
            }
            tokens.add(new Token(Type.STRING, sql.substring(i + 1, close), start, close + delimiter.length()));
            pos = close + delimiter.length();
            return;
        }
        tokens.add(new Token(Type.OTHER, "$", start, start + 1));
        pos++;
    }

    private void readIdentifier() {
        int start = pos;
        while (pos < sql.length() && isIdentifierPart(sql.charAt(pos))) {
            pos++;
        }
        tokens.add(new Token(Type.IDENTIFIER, sql.substring(start, pos).toLowerCase(Locale.ROOT), start, pos));
    }

    private void readNumber() {
        int start = pos;
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            boolean exponentSign = (c == '+' || c == '-') && pos > start
                    && Character.toLowerCase(sql.charAt(pos - 1)) == 'e';
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || exponentSign) {
                pos++;
            } else {
                break;
            }
        }
        tokens.add(new Token(Type.NUMBER, sql.substring(start, pos), start, pos));
    }

    private boolean isPrefixedString(char prefix) {
        return pos + 1 < sql.length()
                && Character.toLowerCase(sql.charAt(pos)) == prefix
                && sql.charAt(pos + 1) == '\'';
    }

    private boolean startsWith(String s) {
        return sql.startsWith(s, pos);
    }

    private boolean startsWithIgnoreCase(String s) {
        return sql.regionMatches(true, pos, s, 0, s.length());
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || Character.isDigit(c) || c == '$';
    }
}
