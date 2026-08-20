package zelisline.ub.platform.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import zelisline.ub.platform.logs.PlatformRequestLogRepository.CategorySummaryRow;

/**
 * Exercises the platform request-log queries the same way the super-admin
 * summary / list endpoints do — catches mapping issues in the native
 * projection or the JPQL search before they hit production.
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:request-log-schema.sql"
})
@DirtiesContext
class PlatformRequestLogRepositoryIT {

    @Autowired
    private PlatformRequestLogRepository repository;

    @Test
    void summaryGroupsByCategoryAndCountsSuccess() {
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        save("1", "GET", "/api/v1/airtime/quote", RequestLogCategory.AIRTIME, 200, true, 12, twoHoursAgo);
        save("2", "POST", "/api/v1/airtime/orders", RequestLogCategory.AIRTIME, 500, false, 300, twoHoursAgo);
        save("3", "POST", "/api/v1/payments/mpesa/stk", RequestLogCategory.MPESA, 200, true, 45, twoHoursAgo);
        save("4", "POST", "/api/v1/sales", RequestLogCategory.CASHIER, 201, true, 60, twoHoursAgo);

        // 3h window: everything above.
        List<CategorySummaryRow> rows = repository.summarySince(Instant.now().minusSeconds(10800));
        assertThat(rows).hasSize(3);
        CategorySummaryRow airtime = row(rows, "AIRTIME");
        assertThat(airtime.getTotal()).isEqualTo(2);
        assertThat(airtime.getOk()).isEqualTo(1);
        assertThat(airtime.getAvgMs()).isEqualTo(156.0);
        assertThat(airtime.getLastAt()).isNotNull();
        assertThat(row(rows, "MPESA").getTotal()).isEqualTo(1);
        assertThat(row(rows, "CASHIER").getTotal()).isEqualTo(1);

        // 1h window: only the fresh KPLC row is included; older categories drop out.
        save("5", "POST", "/api/v1/kplc/tokens", RequestLogCategory.KPLC, 200, true, 5, Instant.now());
        List<CategorySummaryRow> recent = repository.summarySince(Instant.now().minusSeconds(3600));
        assertThat(recent).hasSize(1);
        assertThat(row(recent, "KPLC").getTotal()).isEqualTo(1);

        // Null window: everything.
        assertThat(repository.summarySince(null)).hasSize(4);
    }

    @Test
    void searchAppliesOptionalFilters() {
        save("10", "GET", "/api/v1/airtime/quote", RequestLogCategory.AIRTIME, 200, true, 12);
        save("11", "POST", "/api/v1/airtime/orders", RequestLogCategory.AIRTIME, 500, false, 300);

        assertThat(repository.findAll(PlatformRequestLogRepository.matches(null, null, null, null), PageRequest.of(0, 50)))
                .hasSize(2);
        assertThat(repository.findAll(PlatformRequestLogRepository.matches(RequestLogCategory.AIRTIME, null, null, null), PageRequest.of(0, 50)))
                .hasSize(2);
        assertThat(repository.findAll(PlatformRequestLogRepository.matches(RequestLogCategory.AIRTIME, Boolean.TRUE, null, null), PageRequest.of(0, 50)))
                .hasSize(1);
        assertThat(repository.findAll(PlatformRequestLogRepository.matches(null, Boolean.FALSE, null, null), PageRequest.of(0, 50)))
                .hasSize(1);
        assertThat(repository.findAll(PlatformRequestLogRepository.matches(null, null, null, "203.0.113.5"), PageRequest.of(0, 50)))
                .isEmpty();
        assertThat(repository.findAll(PlatformRequestLogRepository.matches(null, Boolean.FALSE, null, "10.0.0.9"), PageRequest.of(0, 50)))
                .hasSize(1);
    }

    @Test
    void countsExpectedHostLookupMisses() {
        save("20", "GET", "/api/v1/public/host/resolve?host=kiosk.ke", RequestLogCategory.OTHER, 404, false, 3);
        save("21", "GET", "/api/v1/public/host/resolve?host=shop.example.com", RequestLogCategory.OTHER, 200, true, 4);
        save("22", "GET", "/api/v1/public/host/resolve-by-email?email=x@y.z", RequestLogCategory.OTHER, 404, false, 2);
        save("23", "POST", "/api/v1/sales", RequestLogCategory.CASHIER, 500, false, 300);

        // 404s on host-resolve paths are expected misses; other failures are not.
        assertThat(repository.countExpectedMissesSince(null)).isEqualTo(2);

        // Out-of-window rows are excluded.
        save("24", "GET", "/api/v1/public/host/resolve?host=old.example.com",
                RequestLogCategory.OTHER, 404, false, 1, Instant.now().minusSeconds(7200));
        assertThat(repository.countExpectedMissesSince(Instant.now().minusSeconds(3600))).isEqualTo(2);
        assertThat(repository.countExpectedMissesSince(null)).isEqualTo(3);
    }

    private void save(String id, String method, String path,
            RequestLogCategory category, int status, boolean success, long durationMs) {
        PlatformRequestLog row = new PlatformRequestLog();
        row.setId(id);
        row.setLoggedAt(Instant.now());
        row.setMethod(method);
        row.setPath(path);
        row.setCategory(category);
        row.setBusinessId("biz-1");
        row.setStatus(status);
        row.setSuccess(success);
        row.setDurationMs(durationMs);
        row.setIp("10.0.0.9");
        repository.save(row);
    }

    private void save(String id, String method, String path,
            RequestLogCategory category, int status, boolean success, long durationMs,
            Instant loggedAt) {
        PlatformRequestLog row = new PlatformRequestLog();
        row.setId(id);
        row.setLoggedAt(loggedAt);
        row.setMethod(method);
        row.setPath(path);
        row.setCategory(category);
        row.setBusinessId("biz-1");
        row.setStatus(status);
        row.setSuccess(success);
        row.setDurationMs(durationMs);
        row.setIp("10.0.0.9");
        repository.save(row);
    }

    private static CategorySummaryRow row(List<CategorySummaryRow> rows, String category) {
        return rows.stream().filter(r -> category.equals(r.getCategory())).findFirst().orElseThrow();
    }
}
