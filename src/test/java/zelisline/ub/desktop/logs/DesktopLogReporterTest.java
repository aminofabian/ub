package zelisline.ub.desktop.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual "Send logs to support" trigger must never throw and must tell the
 * truth when the till has nowhere to send: with no ingest key configured it
 * reports that plainly instead of pretending the logs were shipped.
 */
class DesktopLogReporterTest {

    @Test
    void sendNowReportsHonestlyWhenNothingIsConfigured(@TempDir Path appData) {
        DesktopLogReporter reporter = new DesktopLogReporter(
            appData.toString(),
            "http://127.0.0.1:1/desktop-logs",
            4096,
            200,
            200,
            "biz-1");

        DesktopLogReporter.LogSendResult result = reporter.sendNow();

        assertThat(result.sent()).isFalse();
        assertThat(result.message()).isNotBlank();
    }
}
