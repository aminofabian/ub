package zelisline.ub.clinic.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.clinic.api.dto.ClinicDtos.TakingsResponse;
import zelisline.ub.clinic.api.dto.ClinicDtos.TakingsRow;
import zelisline.ub.payroll.application.StaffProfileService;

/**
 * What the clinic took in a period, by service and by who did it (ADR 0034, scope section 5).
 * Counts and money only: no patient appears in it. Sales that were voided are left out; a
 * refund made later is not taken off (the figure is what was billed).
 */
@Service
@RequiredArgsConstructor
public class ClinicTakingsService {

    private static final String BY_SERVICE = """
            SELECT si.service_id AS service_id,
                   COALESCE(sv.name, si.line_label, 'Service') AS service_name,
                   si.performer_staff_profile_id AS staff_id,
                   COUNT(*) AS times,
                   COALESCE(SUM(si.line_total), 0) AS total
              FROM pos_drafts d
              JOIN sales s ON s.id = d.sale_id AND s.business_id = d.business_id
              JOIN sale_items si ON si.sale_id = s.id
              LEFT JOIN services sv ON sv.id = si.service_id AND sv.business_id = d.business_id
             WHERE d.business_id = ?
               AND d.branch_id = ?
               AND d.draft_kind = 'SERVICE'
               AND d.patient_id IS NOT NULL
               AND s.status <> 'voided'
               AND s.sold_at >= ? AND s.sold_at < ?
               AND si.line_kind = 'SERVICE'
             GROUP BY si.service_id, COALESCE(sv.name, si.line_label, 'Service'), si.performer_staff_profile_id
             ORDER BY total DESC
            """;

    private static final String TOTALS = """
            SELECT COUNT(DISTINCT d.id) AS visits,
                   COALESCE(SUM(CASE WHEN si.line_kind = 'SERVICE' THEN si.line_total ELSE 0 END), 0) AS services,
                   COALESCE(SUM(CASE WHEN si.line_kind = 'SERVICE' THEN 0 ELSE si.line_total END), 0) AS products
              FROM pos_drafts d
              JOIN sales s ON s.id = d.sale_id AND s.business_id = d.business_id
              JOIN sale_items si ON si.sale_id = s.id
             WHERE d.business_id = ?
               AND d.branch_id = ?
               AND d.draft_kind = 'SERVICE'
               AND d.patient_id IS NOT NULL
               AND s.status <> 'voided'
               AND s.sold_at >= ? AND s.sold_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final StaffProfileService staffProfileService;

    @Transactional(readOnly = true)
    public TakingsResponse takings(String businessId, String branchId, Instant from, Instant to) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        record Raw(String serviceId, String serviceName, String staffId, long times, BigDecimal total) {
        }
        List<Raw> raw = jdbc.query(BY_SERVICE,
                (rs, i) -> new Raw(rs.getString("service_id"), rs.getString("service_name"), rs.getString("staff_id"),
                        rs.getLong("times"), rs.getBigDecimal("total")),
                businessId, branchId, start, end);
        Set<String> staffIds = new LinkedHashSet<>();
        for (Raw r : raw) {
            if (r.staffId() != null) {
                staffIds.add(r.staffId());
            }
        }
        Map<String, String> names = staffIds.isEmpty() ? Map.of() : staffProfileService.displayNamesByIds(businessId, staffIds);
        List<TakingsRow> rows = new ArrayList<>(raw.size());
        for (Raw r : raw) {
            rows.add(new TakingsRow(r.serviceId(), r.serviceName(), r.staffId(),
                    r.staffId() == null ? null : names.getOrDefault(r.staffId(), "Staff"), r.times(), r.total()));
        }
        return jdbc.query(TOTALS, rs -> {
            if (!rs.next()) {
                return new TakingsResponse(from, to, 0, BigDecimal.ZERO, BigDecimal.ZERO, rows);
            }
            return new TakingsResponse(from, to, rs.getLong("visits"), rs.getBigDecimal("services"),
                    rs.getBigDecimal("products"), rows);
        }, businessId, branchId, start, end);
    }
}
