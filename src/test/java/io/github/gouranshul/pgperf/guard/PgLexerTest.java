package io.github.gouranshul.pgperf.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gouranshul.pgperf.guard.PgLexer.Token;
import io.github.gouranshul.pgperf.guard.PgLexer.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

class PgLexerTest {

    @Test
    void foldsUnquotedIdentifiersAndKeepsQuotedCase() {
        List<Token> tokens = PgLexer.tokenize("SELECT Email, \"MixedCase\" FROM Shop.Customers");
        assertThat(tokens).filteredOn(Token::isIdentifier).extracting(Token::value)
                .containsExactly("select", "email", "MixedCase", "from", "shop", "customers");
    }

    @Test
    void doubledQuotesStayInsideStringsAndIdentifiers() {
        assertThat(PgLexer.tokenize("SELECT 'it''s', \"a\"\"b\""))
                .extracting(Token::type, Token::value)
                .contains(org.assertj.core.groups.Tuple.tuple(Type.STRING, "it's"),
                        org.assertj.core.groups.Tuple.tuple(Type.QUOTED_IDENTIFIER, "a\"b"));
    }

    @Test
    void backslashIsLiteralInStandardStringsButEscapesInEStrings() {
        assertThat(types("SELECT 'a\\'; x")).containsExactly(Type.IDENTIFIER, Type.STRING, Type.SEMICOLON, Type.IDENTIFIER);
        assertThat(types("SELECT E'a\\'; x'")).containsExactly(Type.IDENTIFIER, Type.STRING);
    }

    @Test
    void blockCommentsNest() {
        assertThat(types("SELECT /* a /* b */ c; */ 1")).containsExactly(Type.IDENTIFIER, Type.NUMBER);
    }

    @Test
    void lineCommentsRunToEndOfLine() {
        assertThat(types("SELECT 1 -- ; drop\n, 2")).containsExactly(Type.IDENTIFIER, Type.NUMBER, Type.OTHER, Type.NUMBER);
    }

    @Test
    void dollarQuotingAndParameters() {
        assertThat(PgLexer.tokenize("SELECT $fn$ a; b $fn$, $$x$$, $12"))
                .extracting(Token::type, Token::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Type.IDENTIFIER, "select"),
                        org.assertj.core.groups.Tuple.tuple(Type.STRING, " a; b "),
                        org.assertj.core.groups.Tuple.tuple(Type.OTHER, ","),
                        org.assertj.core.groups.Tuple.tuple(Type.STRING, "x"),
                        org.assertj.core.groups.Tuple.tuple(Type.OTHER, ","),
                        org.assertj.core.groups.Tuple.tuple(Type.PARAMETER, "12"));
    }

    @Test
    void identifiersMayContainDollarSigns() {
        assertThat(PgLexer.tokenize("SELECT a$b")).extracting(Token::value).containsExactly("select", "a$b");
    }

    @Test
    void prefixedStringsAndNumbers() {
        assertThat(types("SELECT B'101', X'ff', N'x', U&'d\\0061t', 1.5e-3, .5"))
                .filteredOn(t -> t != Type.OTHER)
                .containsExactly(Type.IDENTIFIER, Type.STRING, Type.STRING, Type.STRING, Type.STRING, Type.NUMBER, Type.NUMBER);
    }

    @Test
    void recordsSourcePositions() {
        Token from = PgLexer.tokenize("SELECT 1 FROM t").get(2);
        assertThat(from.start()).isEqualTo(9);
        assertThat(from.end()).isEqualTo(13);
    }

    @Test
    void decodesUnicodeEscapedIdentifiersAsPostgresDoes() {
        assertThat(PgLexer.tokenize("SELECT U&\"\\0070g_sleep\"(1)").get(1).value()).isEqualTo("pg_sleep");
        assertThat(PgLexer.decodeUnicodeEscapes("a\\+000062\\\\c")).isEqualTo("ab\\c");
        assertThatThrownBy(() -> PgLexer.tokenize("SELECT U&\"\\00zz\""))
                .isInstanceOf(SqlRejectedException.class).hasMessageContaining("Unicode escape");
        assertThatThrownBy(() -> PgLexer.tokenize("SELECT U&\"\\00\""))
                .isInstanceOf(SqlRejectedException.class).hasMessageContaining("Unicode escape");
    }

    @Test
    void rejectsUescapeClauses() {
        assertThatThrownBy(() -> PgLexer.tokenize("SELECT U&\"!0070g_sleep\" UESCAPE '!' (1)"))
                .isInstanceOf(SqlRejectedException.class).hasMessageContaining("UESCAPE");
    }

    @Test
    void rejectsUnterminatedQuotedIdentifier() {
        assertThatThrownBy(() -> PgLexer.tokenize("SELECT U&\"abc"))
                .isInstanceOf(SqlRejectedException.class)
                .hasMessageContaining("quoted identifier");
    }

    private static List<Type> types(String sql) {
        return PgLexer.tokenize(sql).stream().map(Token::type).toList();
    }
}
