package zelisline.ub.configuration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migration strategy.
 *
 * <p>Aligns with {@code spring.flyway.repair-on-migrate=true}: clear failed history
 * rows / checksum drift, then migrate. Without {@code repair()}, a single failed
 * migration (e.g. V204) blocks every subsequent boot forever.
 *
 * <p>Repair only removes failed entries and realigns checksums; it does not drop
 * schema objects. Migrations that are not idempotent can still fail on retry —
 * fix the SQL in that case, then redeploy.
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            warnOnCollationMismatch(flyway);
            flyway.repair();
            flyway.migrate();
        };
    }

    /**
     * Turns a cryptic mid-chain failure into a named cause.
     *
     * <p>No migration specifies {@code COLLATE} or {@code CHARACTER SET}, so every
     * column inherits the <em>database's</em> default collation. V123 then compares
     * those columns against a session variable, and MySQL refuses to mix an implicit
     * column collation with an implicit connection collation (error 1267). On a fresh
     * MySQL 8/9 the server default is {@code utf8mb4_0900_ai_ci}, so this only bites
     * when the database was deliberately created with a different collation — for
     * example {@code CREATE DATABASE ub ... COLLATE utf8mb4_unicode_ci}.
     *
     * <p>The remedy is not to hard-code a collation: pinning a value here would break
     * the perfectly good database that was created with the server default. So the
     * check is diagnostic and states both options.
     *
     * <p>It speaks only when there is something to migrate (a healthy or
     * already-migrated database stays quiet), and it must never throw — a diagnostic
     * that can block a boot is worse than the error it describes.
     */
    private static void warnOnCollationMismatch(Flyway flyway) {
        try {
            if (flyway.info().pending().length == 0) {
                return;
            }
            DataSource dataSource = flyway.getConfiguration().getDataSource();
            if (dataSource == null) {
                return;
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                String connectionCollation = single(statement, "SELECT @@collation_connection");
                String schemaCollation = single(statement,
                        "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                                + "WHERE SCHEMA_NAME = DATABASE()");
                if (connectionCollation == null
                        || schemaCollation == null
                        || connectionCollation.equals(schemaCollation)) {
                    return;
                }
                log.warn("Database collation mismatch — migrations are likely to fail. "
                                + "This schema defaults to '{}' but the JDBC connection collation is '{}'. "
                                + "Migrations add columns with no explicit COLLATE, so they inherit '{}', "
                                + "and V123 compares such a column against a session variable, which MySQL "
                                + "rejects with error 1267 part-way through. Either recreate the database "
                                + "with the server default collation, or append ?connectionCollation={} "
                                + "to spring.datasource.url so the connection matches the schema.",
                        schemaCollation, connectionCollation, schemaCollation, schemaCollation);
            }
        } catch (Exception ex) {
            // Includes engines without information_schema.SCHEMATA (e.g. H2 in tests).
            log.debug("Collation preflight skipped: {}", ex.getMessage());
        }
    }

    private static String single(Statement statement, String sql) throws java.sql.SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
