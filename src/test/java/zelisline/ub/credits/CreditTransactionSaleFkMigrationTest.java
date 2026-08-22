package zelisline.ub.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * Reproduces the production failure where {@code credit_transactions.sale_id} (a
 * polymorphic source id — sale id, payment claim id, M-Pesa intent id, or reversal
 * source id) is constrained by the V30 FK to {@code sales(id)}, and proves the
 * V232 migration (which drops that FK) resolves it.
 */
class CreditTransactionSaleFkMigrationTest {

    @Test
    void v232DropsFkThatRejectsNonSaleSourceIds() throws Exception {
        String url = "jdbc:h2:mem:fkmig;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            Statement st = c.createStatement();
            st.execute("""
                    CREATE TABLE sales (
                      id CHAR(36) PRIMARY KEY,
                      business_id CHAR(36) NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE credit_transactions (
                      id CHAR(36) PRIMARY KEY,
                      business_id CHAR(36) NOT NULL,
                      credit_account_id CHAR(36) NOT NULL,
                      sale_id CHAR(36) NULL,
                      txn_type VARCHAR(24) NOT NULL,
                      amount DECIMAL(14, 2) NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_credit_txn_sale FOREIGN KEY (sale_id) REFERENCES sales (id)
                    )
                    """);

            String saleId = "11111111-1111-1111-1111-111111111111";
            st.execute("INSERT INTO sales (id, business_id) VALUES ('" + saleId + "', 'b1')");

            // A payment linked to a real sale satisfies the FK.
            insert(st, "11111111-2222-3333-4444-555555555555", saleId);

            // A payment whose sale_id is a claim id (not a sales row) is rejected by V30's FK.
            String claimId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
            Throwable rejected = catchThrowable(
                    () -> insert(st, "11111111-2222-3333-4444-666666666666", claimId));
            assertThat(rejected).isInstanceOf(SQLException.class);
            assertThat(rejected.getMessage().toLowerCase())
                    .contains("fk_credit_txn_sale");

            // Apply the V232 migration.
            String migrationSql = Files.readString(Path.of(
                    "src/main/resources/db/migration/V232__credit_transactions_drop_sale_fk.sql"));
            st.execute(migrationSql);

            // The same non-sale source id now persists (as production requires).
            assertThatCode(() -> insert(st, "11111111-2222-3333-4444-666666666666", claimId))
                    .doesNotThrowAnyException();
        }
    }

    private static void insert(Statement st, String id, String saleId) throws SQLException {
        st.execute("""
                INSERT INTO credit_transactions (id, business_id, credit_account_id, sale_id, txn_type, amount)
                VALUES ('%s', 'b1', '11111111-1111-1111-1111-111111111122', '%s', 'payment', 150.00)
                """.formatted(id, saleId));
    }
}
