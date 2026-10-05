package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.tools.LockTools.BlockingPair;
import io.github.gouranshul.pgperf.tools.LockTools.BlockingReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockToolsTest {

    @Test
    void rootBlockersAreBlockersThatAreNotThemselvesBlocked() {
        // 30 blocks 20, 20 blocks 10 and 11: the chain starts at 30.
        BlockingReport report = LockTools.summarize(List.of(
                pair(20, 30), pair(10, 20), pair(11, 20)));
        assertThat(report.rootBlockers()).containsExactly(30);
        assertThat(report.blockedSessions()).isEqualTo(3);
        assertThat(report.hint()).contains("will not do it");
    }

    @Test
    void noWaitsMeansNoBlockers() {
        BlockingReport report = LockTools.summarize(List.of());
        assertThat(report.rootBlockers()).isEmpty();
        assertThat(report.hint()).isEqualTo("No lock waits right now.");
    }

    private static BlockingPair pair(int blocked, int blocking) {
        return new BlockingPair(blocked, "app", "UPDATE ...", 1.0, "transactionid", "ShareLock", null, blocking,
                "app", "active", "UPDATE ...", 2.0);
    }
}
