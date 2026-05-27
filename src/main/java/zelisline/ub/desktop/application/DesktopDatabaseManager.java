package zelisline.ub.desktop.application;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Verifies the bundled MariaDB is reachable and reports its version at startup
 * for the desktop SKU (see {@code DESKTOP_INSTALLATION.md} §7).
 *
 * <p>The Tauri shell / launcher is responsible for extracting the MariaDB
 * binary, running {@code mariadb-install-db}, generating a random password,
 * and starting {@code mariadbd} on {@code 127.0.0.1:${APP_DESKTOP_DB_PORT:33306}}
 * before launching the JVM. This component's job is to <em>verify</em> that
 * step succeeded before Spring starts serving traffic.
 *
 * <p>It also registers as an Actuator {@link HealthIndicator} so the launcher
 * can poll {@code /actuator/health} to confirm the database is ready — the
 * launcher must not open the browser until the JVM + DB are both live.
 *
 * <p>On a cloud install this bean is never created ({@code @Profile("desktop")}).
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopDatabaseManager implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
        DesktopDatabaseManager.class
    );

    private final DataSource dataSource;

    @Value("${spring.datasource.url:unknown}")
    private String datasourceUrl;

    // Mutable so the health endpoint always reflects current state, not just
    // the startup snapshot.
    private volatile String dbProductName;
    private volatile String dbVersion;

    /**
     * Runs after the Spring context is fully refreshed and the web server is
     * accepting connections. A failure here leaves the process alive (so
     * Actuator can report the DOWN status) but logs the error so the launcher
     * can surface it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            dbProductName = meta.getDatabaseProductName();
            dbVersion = meta.getDatabaseProductVersion();
            log.info(
                "[DesktopDB] connected to {} {} on {}. Flyway will run migrations next.",
                dbProductName,
                dbVersion,
                datasourceUrl
            );
        } catch (SQLException e) {
            dbProductName = null;
            dbVersion = null;
            log.error(
                "[DesktopDB] cannot reach the database at {}. " +
                    "Is mariadbd running on the expected port? " +
                    "The launcher must start it before launching the JVM. " +
                    "Error: {}",
                datasourceUrl,
                e.getMessage()
            );
        }
    }

    @Override
    public Health health() {
        if (dbProductName != null) {
            return Health.up()
                .withDetail("product", dbProductName)
                .withDetail("version", dbVersion)
                .withDetail("url", datasourceUrl)
                .build();
        }
        return Health.down()
            .withDetail("url", datasourceUrl)
            .withDetail(
                "message",
                "Database not reachable. Ensure mariadbd is running on the expected port."
            )
            .build();
    }
}
