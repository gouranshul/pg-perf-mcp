package io.github.gouranshul.pgperf.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gouranshul.pgperf.config.PgPerfProperties.Guard;
import io.github.gouranshul.pgperf.config.PgPerfProperties.Query;
import io.github.gouranshul.pgperf.config.PgPerfProperties.Security;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigUnitTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingApiKeyPreventsStartup(String key) {
        assertThatThrownBy(() -> new Security(key)).hasMessageContaining("MCP_API_KEY");
    }

    @Test
    void shortApiKeyPreventsStartup() {
        assertThatThrownBy(() -> new Security("short")).hasMessageContaining("at least 16");
    }

    @Test
    void apiKeyIsNeverPrinted() {
        assertThat(new Security("a-very-secret-api-key-123").toString()).doesNotContain("secret");
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new Guard(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(Duration.ofSeconds(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiKeyFilterMatchesOnlyTheExactKey() {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter("the-real-key-0123456789");
        assertThat(filter.matches("the-real-key-0123456789")).isTrue();
        assertThat(filter.matches("the-real-key-012345678")).isFalse();
        assertThat(filter.matches("the-real-key-01234567890")).isFalse();
        assertThat(filter.matches("")).isFalse();
    }
}
