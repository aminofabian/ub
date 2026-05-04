package zelisline.ub.integrations.backup.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.backup.domain.BackupRun;

/**
 * Invokes {@code mysqldump} or {@code pg_dump} using credentials from the app's {@link DataSource}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalProcessDatabaseDumper {

    private static final int TIMEOUT_HOURS = 4;

    private final DataSource dataSource;

    public void dumpToFile(Path outputFile) throws IOException, InterruptedException {
        if (!(dataSource instanceof HikariDataSource h)) {
            throw new IllegalStateException("Backup requires HikariDataSource, got " + dataSource.getClass());
        }
        JdbcDumpTarget target = JdbcDumpTarget.fromJdbcUrl(h.getJdbcUrl());
        String user = h.getUsername() != null ? h.getUsername() : "";
        String password = h.getPassword() != null ? h.getPassword() : "";

        Files.createDirectories(outputFile.getParent());

        switch (target.engine()) {
            case mysql -> runMysqlDump(target, user, password, outputFile);
            case postgres -> runPgDump(target, user, password, outputFile);
            default -> throw new UnsupportedOperationException(target.engine().name());
        }
    }

    private void runMysqlDump(JdbcDumpTarget t, String user, String password, Path outputFile)
            throws IOException, InterruptedException {
        Path cnf = Files.createTempFile("ub-mysqldump-", ".cnf");
        try {
            String ini =
                    "[client]\n" + "user=" + user + "\n" + "password=" + password + "\n" + "host=" + t.host() + "\n"
                            + "port=" + t.port() + "\n";
            Files.writeString(cnf, ini, StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>();
            cmd.add("mysqldump");
            cmd.add("--defaults-extra-file=" + cnf.toAbsolutePath());
            cmd.add("--single-transaction");
            cmd.add("--routines");
            cmd.add("--events");
            cmd.add("--hex-blob");
            cmd.add("--result-file=" + outputFile.toAbsolutePath());
            cmd.add(t.database());
            runProcess(cmd, BackupRun.Engine.mysql);
        } finally {
            Files.deleteIfExists(cnf);
        }
    }

    private void runPgDump(JdbcDumpTarget t, String user, String password, Path outputFile)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-h",
                t.host(),
                "-p",
                String.valueOf(t.port()),
                "-U",
                user,
                "-Fc",
                "-f",
                outputFile.toAbsolutePath().toString(),
                t.database());
        pb.environment().put("PGPASSWORD", password);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drainToLog(p, BackupRun.Engine.postgres);
        if (!p.waitFor(TIMEOUT_HOURS, TimeUnit.HOURS)) {
            p.destroyForcibly();
            throw new IOException("pg_dump timed out");
        }
        if (p.exitValue() != 0) {
            throw new IOException("pg_dump exited with " + p.exitValue());
        }
    }

    private void runProcess(List<String> cmd, BackupRun.Engine engine) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drainToLog(p, engine);
        if (!p.waitFor(TIMEOUT_HOURS, TimeUnit.HOURS)) {
            p.destroyForcibly();
            throw new IOException(engine + " dump timed out");
        }
        if (p.exitValue() != 0) {
            throw new IOException(engine + " dump exited with " + p.exitValue());
        }
    }

    private void drainToLog(Process p, BackupRun.Engine engine) throws IOException {
        try (var in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                log.debug("[{} dump] {}", engine, new String(buf, 0, n, StandardCharsets.UTF_8).trim());
            }
        }
    }
}
