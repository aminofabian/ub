package zelisline.ub.storefront;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

import org.junit.jupiter.api.Test;

/**
 * V348 (scope P0-1): every unpaid order now carries a stock-expiry window, so the sweeper can
 * release an abandoned online order's hold — previously only WhatsApp orders had one. The
 * migration backfills existing {@code pending_payment} rows without a window. It is deliberately
 * date-function-free ({@code expires_at = created_at}) so it runs on MySQL/MariaDB and Postgres.
 */
class WebOrderExpiryBackfillMigrationTest {

    @Test
    void v348BackfillsOnlyUnpaidOrdersMissingAWindow() throws Exception {
        String url = "jdbc:h2:mem:woexpiry;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            Statement st = c.createStatement();
            st.execute("""
                    CREATE TABLE web_orders (
                      id CHAR(36) PRIMARY KEY,
                      status VARCHAR(24) NOT NULL,
                      fulfillment_status VARCHAR(24) NULL,
                      handoff_state VARCHAR(24) NULL,
                      expires_at TIMESTAMP NULL,
                      created_at TIMESTAMP NOT NULL
                    )
                    """);
            // Abandoned WEB payment, no window -> backfilled to its created_at (immediately due).
            insert(st, "o-web", "'pending_payment'", "NULL", "NULL", "NULL", "'2026-01-01 10:00:00'");
            // Already carries a window -> untouched.
            insert(st, "o-wa", "'pending_payment'", "'awaiting_confirmation'", "NULL",
                    "'2026-01-02 10:00:00'", "'2026-01-01 10:00:00'");
            // Already released -> untouched.
            insert(st, "o-exp", "'pending_payment'", "NULL", "'expired'", "NULL", "'2026-01-01 10:00:00'");
            // Paid -> untouched.
            insert(st, "o-paid", "'paid'", "NULL", "NULL", "NULL", "'2026-01-01 10:00:00'");

            String migrationSql = Files.readString(Path.of(
                    "src/main/resources/db/migration/V348__web_orders_expiry_all_channels_backfill.sql"));
            st.execute(migrationSql);

            assertThat(column(st, "o-web", "expires_at"))
                    .isNotNull()
                    .isEqualTo(column(st, "o-web", "created_at"));
            assertThat(column(st, "o-wa", "expires_at"))
                    .isEqualTo(Timestamp.valueOf("2026-01-02 10:00:00"));
            assertThat(column(st, "o-exp", "expires_at")).isNull();
            assertThat(column(st, "o-paid", "expires_at")).isNull();
        }
    }

    private static void insert(
            Statement st, String id, String status, String fulfillment,
            String handoff, String expires, String created) throws Exception {
        st.execute(("INSERT INTO web_orders "
                + "(id, status, fulfillment_status, handoff_state, expires_at, created_at) "
                + "VALUES ('%s', %s, %s, %s, %s, %s)")
                .formatted(id, status, fulfillment, handoff, expires, created));
    }

    private static Timestamp column(Statement st, String id, String column) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT " + column + " FROM web_orders WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getTimestamp(column);
        }
    }
}
