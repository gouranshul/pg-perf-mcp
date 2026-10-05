package io.github.gouranshul.pgperf.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import tools.jackson.databind.json.JsonMapper;

/** Loads EXPLAIN (FORMAT JSON) fixtures from {@code src/test/resources/plans}. */
final class Plans {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Plans() {
    }

    static ExplainResult load(String name) {
        try (InputStream in = Plans.class.getResourceAsStream("/plans/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return ExplainResult.parse(JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ExplainResult parse(String json) {
        return ExplainResult.parse(JSON.readTree(json));
    }
}
