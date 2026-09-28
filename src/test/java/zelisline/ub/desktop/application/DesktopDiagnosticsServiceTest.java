package zelisline.ub.desktop.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import zelisline.ub.desktop.api.dto.DesktopLogFile;
import zelisline.ub.desktop.api.dto.DesktopStorageStatus;

/**
 * Settings → Desktop diagnostics: the data-folder facts and log tails must be
 * bounded and must never fail when a file is missing (the endpoint is exactly
 * what an operator reaches for when something is already wrong).
 */
class DesktopDiagnosticsServiceTest {

    private DesktopDiagnosticsService service(Path appData) {
        DesktopDiagnosticsService s = new DesktopDiagnosticsService();
        ReflectionTestUtils.setField(s, "appData", appData.toString());
        return s;
    }

    @Test
    void logsReportTailAndMarkMissingFiles(@TempDir Path appData) throws Exception {
        Files.writeString(
            appData.resolve("kiosk.log"), "line1\nline2\n", StandardCharsets.UTF_8);

        List<DesktopLogFile> logs = service(appData).logs();

        assertThat(logs).extracting(DesktopLogFile::name)
            .containsExactly(
                "kiosk.log", "backend.out.log", "backend.err.log", "mariadb.log");
        DesktopLogFile kiosk = logs.get(0);
        assertThat(kiosk.bytes()).isEqualTo(Files.size(appData.resolve("kiosk.log")));
        assertThat(kiosk.tail()).contains("line1").contains("line2");
        DesktopLogFile missing = logs.get(1);
        assertThat(missing.bytes()).isEqualTo(-1);
        assertThat(missing.tail()).isEmpty();
    }

    @Test
    void storageReportsDataPathAndFolderSizes(@TempDir Path appData) throws Exception {
        Files.createDirectories(appData.resolve("db"));
        Files.write(appData.resolve("db").resolve("ibdata1"), new byte[2048]);
        Files.createDirectories(appData.resolve("media"));
        Files.write(appData.resolve("media").resolve("photo.jpg"), new byte[1024]);

        DesktopStorageStatus storage = service(appData).storage();

        assertThat(storage.appDataPath()).isEqualTo(appData.toAbsolutePath().toString());
        assertThat(storage.databaseBytes()).isEqualTo(2048);
        assertThat(storage.mediaBytes()).isEqualTo(1024);
        assertThat(storage.backupsBytes()).isZero();
        assertThat(storage.diskTotalBytes()).isPositive();
    }
}
