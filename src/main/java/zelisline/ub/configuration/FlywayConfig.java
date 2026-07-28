package zelisline.ub.configuration;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migration strategy.
 *
 * Do not call {@code flyway.repair()} on every startup. Repair records DELETE
 * markers in flyway_schema_history that unmark prior successful applies of the
 * same version. If a migration then fails because objects already exist
 * (e.g. V1 CREATE TABLE), the next boot repairs again and re-attempts forever.
 *
 * Clear a stuck failed row manually when needed:
 * {@code DELETE FROM flyway_schema_history WHERE success = 0;}
 * then fix checksums with a one-off {@code flyway repair} if required.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy migrateOnly() {
        return flyway -> flyway.migrate();
    }
}
