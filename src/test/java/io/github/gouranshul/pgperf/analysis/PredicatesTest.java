package io.github.gouranshul.pgperf.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gouranshul.pgperf.analysis.Predicates.Kind;
import io.github.gouranshul.pgperf.analysis.Predicates.Predicate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.api.Test;

class PredicatesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "(customer_id = 42)|customer_id|EQUALITY",
            "(o.customer_id = $1)|customer_id|EQUALITY",
            "((status)::text = 'paid'::text)|status|EQUALITY",
            "(customer_id = ANY ('{1,2,3}'::bigint[]))|customer_id|EQUALITY",
            "(last_login IS NULL)|last_login|EQUALITY",
            "(created_at > (now() - '30 days'::interval))|created_at|RANGE",
            "(total <= 100.00)|total|RANGE",
            "(name ~~ '%Mug%'::text)|name|INFIX_MATCH",
            "(name ~~* 'smart%'::text)|name|PREFIX_MATCH",
            "(42 = customer_id)|customer_id|EQUALITY",
            "(\"Weird Col\" = 1)|Weird Col|EQUALITY",
    })
    void classifiesSingleComparisons(String condition, String column, Kind kind) {
        assertThat(Predicates.parse(condition)).singleElement().satisfies(p -> {
            assertThat(p.column()).isEqualTo(column);
            assertThat(p.kind()).isEqualTo(kind);
        });
    }

    @Test
    void splitsTopLevelAndAndKeepsQualifiers() {
        assertThat(Predicates.parse(
                "((c.country = 'NL'::bpchar) AND (c.created_at > (now() - '30 days'::interval)))"))
                .extracting(Predicate::qualifier, Predicate::column, Predicate::kind)
                .containsExactly(tuple("c", "country", Kind.EQUALITY), tuple("c", "created_at", Kind.RANGE));
    }

    @Test
    void recognizesJoinConditions() {
        assertThat(Predicates.parse("(o.customer_id = c.id)")).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(Kind.JOIN);
            assertThat(p.qualifier()).isEqualTo("o");
            assertThat(p.otherQualifier()).isEqualTo("c");
            assertThat(p.otherColumn()).isEqualTo("id");
        });
    }

    @Test
    void ignoresOrExpressionsFunctionsAndAndInsideStrings() {
        assertThat(Predicates.parse("((status = 'a'::text) OR (status = 'b'::text))")).isEmpty();
        assertThat(Predicates.parse("(lower(email) = 'x'::text)")).isEmpty();
        assertThat(Predicates.parse("(note = 'this AND that'::text)")).singleElement()
                .extracting(Predicate::column).isEqualTo("note");
        assertThat(Predicates.parse("(flag = true)")).singleElement().extracting(Predicate::column).isEqualTo("flag");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void emptyConditions(String condition) {
        assertThat(Predicates.parse(condition)).isEmpty();
    }
}
