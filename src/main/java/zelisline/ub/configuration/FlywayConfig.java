package zelisline.ub.configuration;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migration strategy.
 *
 * Aligns with {@code spring.flyway.repair-on-migrate=true}: clear failed history
 * rows / checksum drift, then migrate. Without {@code repair()}, a single failed
 * migration (e.g. V204) blocks every subsequent boot forever.
 *
 * Repair only removes failed entries and realigns checksums; it does not drop
 * schema objects. Migrations that are not idempotent can still fail on retry —
 * fix the SQL in that case, then redeploy.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
