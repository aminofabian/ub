package zelisline.ub.integrations.backup.application;

import zelisline.ub.integrations.backup.domain.BackupRun;

/**
 * Parsed {@code spring.datasource.url} for {@code mysqldump} / {@code pg_dump} invocation.
 */
public record JdbcDumpTarget(BackupRun.Engine engine, String host, int port, String database) {

    public static JdbcDumpTarget fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is blank");
        }
        if (jdbcUrl.startsWith("jdbc:h2:")) {
            throw new UnsupportedOperationException("H2 backups are not supported; use MySQL or PostgreSQL.");
        }
        if (jdbcUrl.startsWith("jdbc:mysql:")) {
            return parseAfterScheme(jdbcUrl, "jdbc:mysql://", BackupRun.Engine.mysql, 3306);
        }
        if (jdbcUrl.startsWith("jdbc:mariadb:")) {
            return parseAfterScheme(jdbcUrl, "jdbc:mariadb://", BackupRun.Engine.mysql, 3306);
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            return parseAfterScheme(jdbcUrl, "jdbc:postgresql://", BackupRun.Engine.postgres, 5432);
        }
        throw new UnsupportedOperationException("Unsupported JDBC URL for backup: " + jdbcUrl);
    }

    private static JdbcDumpTarget parseAfterScheme(String jdbcUrl, String prefix, BackupRun.Engine engine, int defaultPort) {
        String rest = jdbcUrl.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Expected /database in JDBC URL");
        }
        int q = rest.indexOf('?', slash);
        String db = rest.substring(slash + 1, q >= 0 ? q : rest.length());
        if (db.isBlank()) {
            throw new IllegalArgumentException("Database name missing in JDBC URL");
        }
        String hostPort = rest.substring(0, slash);
        String host;
        int port;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(']') < 0) {
            // ipv6 [addr]:port — skip for MVP; document standard host:port
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else if (colon > 0 && hostPort.startsWith("[") && hostPort.contains("]:")) {
            int close = hostPort.indexOf(']');
            host = hostPort.substring(1, close);
            int p = hostPort.indexOf(':', close);
            port = Integer.parseInt(hostPort.substring(p + 1));
        } else {
            host = hostPort;
            port = defaultPort;
        }
        return new JdbcDumpTarget(engine, host, port, db);
    }
}
