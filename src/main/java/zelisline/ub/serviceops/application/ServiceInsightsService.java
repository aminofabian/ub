package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.serviceops.domain.StaffLedgerKinds;
import zelisline.ub.serviceops.api.dto.CommissionDtos.EarningsSmsResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.ServiceReportResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.ServiceReportRow;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StaffReportRow;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StatementResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StatementRow;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Read-only views over what is already recorded: sale lines and the staff ledger. No table of
 * its own (scope §15).
 */
@Service
@RequiredArgsConstructor
public class ServiceInsightsService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    @PersistenceContext
    private EntityManager entityManager;

    private final StaffProfileService staffProfileService;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffEarningsService staffEarningsService;
    private final ServiceConsumablesService serviceConsumablesService;
    private final BusinessRepository businessRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;
    private final zelisline.ub.serviceops.repository.ServiceResourceRepository serviceResourceRepository;
    private final zelisline.ub.serviceops.repository.ServiceOfferingRepository serviceOfferingRepository;
    private final zelisline.ub.tenancy.repository.BranchRepository branchRepository;

    /**
     * What each chair, bed or room at a branch brought in (Africa/Nairobi days, both inclusive):
     * the value of the services done on it, from visits that were paid. Voided and fully refunded
     * sales are left out, as in {@link #report}. Every place of the branch is listed, busy or not,
     * so an idle chair shows as idle rather than missing. Work paid for with no place recorded is
     * returned apart, never spread across the chairs.
     */
    @Transactional(readOnly = true)
    public zelisline.ub.serviceops.api.dto.CommissionDtos.ChairReportResponse chairReport(
            String businessId, LocalDate from, LocalDate to, String branchId) {
        // The branch must be this business's own: another shop's branch is not found, not empty.
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Branch not found"));
        Instant start = from.atStartOfDay(NAIROBI).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        List<Object[]> lines = entityManager.createQuery("""
                select l.resourceId, l.draftId, l.lineTotal, l.serviceId
                  from PosDraftLine l, PosDraft d, Sale s
                 where l.draftId = d.id
                   and d.saleId = s.id
                   and d.businessId = :businessId
                   and l.businessId = :businessId
                   and s.businessId = :businessId
                   and d.branchId = :branch
                   and s.status = 'completed'
                   and l.deleted = false
                   and l.serviceId is not null
                   and s.soldAt >= :start and s.soldAt < :end
                """, Object[].class)
                .setParameter("businessId", businessId)
                .setParameter("branch", branchId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();

        Map<String, long[]> counts = new LinkedHashMap<>();          // [services, minutes]
        Map<String, BigDecimal> money = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> visits = new LinkedHashMap<>();
        Map<String, Integer> minutesByService = new LinkedHashMap<>();
        BigDecimal unplaced = ZERO;
        long unplacedServices = 0;
        BigDecimal total = ZERO;
        for (Object[] r : lines) {
            String resourceId = (String) r[0];
            BigDecimal value = r[2] == null ? ZERO : (BigDecimal) r[2];
            total = total.add(value);
            if (resourceId == null) {
                unplaced = unplaced.add(value);
                unplacedServices++;
                continue;
            }
            String serviceId = (String) r[3];
            int minutes = minutesByService.computeIfAbsent(serviceId, id -> serviceOfferingRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(id, businessId).map(o -> o.getDurationMinutes()).orElse(0));
            long[] c = counts.computeIfAbsent(resourceId, k -> new long[2]);
            c[0]++;
            c[1] += minutes;
            money.merge(resourceId, value, BigDecimal::add);
            visits.computeIfAbsent(resourceId, k -> new java.util.HashSet<>()).add((String) r[1]);
        }

        List<zelisline.ub.serviceops.api.dto.CommissionDtos.ChairReportRow> rows = new ArrayList<>();
        for (zelisline.ub.serviceops.domain.ServiceResource place : serviceResourceRepository
                .findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(businessId, branchId)) {
            long[] c = counts.getOrDefault(place.getId(), new long[2]);
            if (!place.isActive() && c[0] == 0) {
                continue;
            }
            rows.add(new zelisline.ub.serviceops.api.dto.CommissionDtos.ChairReportRow(
                    place.getId(), place.getName(), place.getResourceType(), place.isActive(), c[0],
                    visits.getOrDefault(place.getId(), java.util.Set.of()).size(),
                    money.getOrDefault(place.getId(), ZERO).setScale(2, RoundingMode.HALF_UP), c[1]));
        }
        return new zelisline.ub.serviceops.api.dto.CommissionDtos.ChairReportResponse(
                from, to, branchId, rows, total.setScale(2, RoundingMode.HALF_UP),
                unplaced.setScale(2, RoundingMode.HALF_UP), unplacedServices);
    }

    /**
     * Revenue, commission and contribution per service and per staff member (Africa/Nairobi days,
     * both inclusive). Voided and fully refunded sales are left out; a part refund still counts
     * the line's revenue in full, while its commission is already net of the reversal.
     */
    @Transactional(readOnly = true)
    public ServiceReportResponse report(String businessId, LocalDate from, LocalDate to, String branchId) {
        Instant start = from.atStartOfDay(NAIROBI).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        String branch = branchId == null || branchId.isBlank() ? null : branchId.trim();

        Map<String, long[]> serviceCounts = new LinkedHashMap<>();   // [times, minutes]
        Map<String, BigDecimal[]> serviceMoney = new LinkedHashMap<>(); // [revenue, commission]
        Map<String, String> serviceNames = new LinkedHashMap<>();
        Map<String, long[]> staffCounts = new LinkedHashMap<>();     // [times, minutes, redos]
        Map<String, BigDecimal[]> staffMoney = new LinkedHashMap<>(); // [revenue, commission, tips]

        List<Object[]> lines = entityManager.createQuery("""
                select i.serviceId, i.displayName, i.performerStaffProfileId, i.quantity, i.lineTotal, i.serviceMinutes
                  from SaleItem i, Sale s
                 where s.id = i.saleId
                   and s.businessId = :businessId
                   and s.status = 'completed'
                   and i.lineKind = 'SERVICE'
                   and s.soldAt >= :start and s.soldAt < :end
                   and (:branch is null or s.branchId = :branch)
                """, Object[].class)
                .setParameter("businessId", businessId)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("branch", branch)
                .getResultList();
        for (Object[] r : lines) {
            String serviceId = (String) r[0];
            String staffId = (String) r[2];
            long times = r[3] == null ? 1 : ((BigDecimal) r[3]).setScale(0, RoundingMode.HALF_UP).longValue();
            BigDecimal total = r[4] == null ? ZERO : (BigDecimal) r[4];
            long minutes = (r[5] == null ? 0 : ((Number) r[5]).longValue()) * times;
            if (serviceId != null) {
                serviceNames.put(serviceId, (String) r[1]);
                long[] c = serviceCounts.computeIfAbsent(serviceId, k -> new long[2]);
                c[0] += times;
                c[1] += minutes;
                BigDecimal[] m = serviceMoney.computeIfAbsent(serviceId, k -> new BigDecimal[] { ZERO, ZERO });
                m[0] = m[0].add(total);
            }
            if (staffId != null) {
                long[] c = staffCounts.computeIfAbsent(staffId, k -> new long[3]);
                c[0] += times;
                c[1] += minutes;
                BigDecimal[] m = staffMoney.computeIfAbsent(staffId, k -> new BigDecimal[] { ZERO, ZERO, ZERO });
                m[0] = m[0].add(total);
            }
        }

        // Commission and tips as the staff ledger has them, reversals included, so a refunded
        // haircut does not leave its commission in the report.
        List<Object[]> earned = entityManager.createQuery("""
                select i.serviceId, e.staffProfileId, e.bucket, e.amount
                  from StaffLedgerEntry e left join SaleItem i on i.id = e.saleItemId
                 where e.businessId = :businessId
                   and e.kind in ('COMMISSION', 'TIP', 'REVERSAL')
                   and e.occurredAt >= :start and e.occurredAt < :end
                   and (:branch is null or e.branchId = :branch)
                """, Object[].class)
                .setParameter("businessId", businessId)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("branch", branch)
                .getResultList();
        for (Object[] r : earned) {
            String serviceId = (String) r[0];
            String staffId = (String) r[1];
            boolean tips = StaffLedgerKinds.BUCKET_TIPS.equals(r[2]);
            BigDecimal amount = r[3] == null ? ZERO : (BigDecimal) r[3];
            BigDecimal[] m = staffMoney.computeIfAbsent(staffId, k -> new BigDecimal[] { ZERO, ZERO, ZERO });
            if (tips) {
                m[2] = m[2].add(amount);
            } else {
                m[1] = m[1].add(amount);
                if (serviceId != null) {
                    BigDecimal[] sm = serviceMoney.computeIfAbsent(serviceId, k -> new BigDecimal[] { ZERO, ZERO });
                    sm[1] = sm[1].add(amount);
                }
            }
        }

        List<Object[]> redos = entityManager.createQuery("""
                select d.requestedStaffProfileId, count(d)
                  from PosDraft d
                 where d.businessId = :businessId
                   and d.draftKind = 'SERVICE'
                   and d.serviceStage = 'REDO_DONE'
                   and d.completedAt >= :start and d.completedAt < :end
                   and (:branch is null or d.branchId = :branch)
                 group by d.requestedStaffProfileId
                """, Object[].class)
                .setParameter("businessId", businessId)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("branch", branch)
                .getResultList();
        for (Object[] r : redos) {
            if (r[0] != null) {
                staffCounts.computeIfAbsent((String) r[0], k -> new long[3])[2] += ((Number) r[1]).longValue();
            }
        }

        List<ServiceReportRow> serviceRows = new ArrayList<>();
        BigDecimal revenue = ZERO;
        BigDecimal commission = ZERO;
        BigDecimal productCost = ZERO;
        Map<String, BigDecimal> costPerVisit =
                serviceConsumablesService.costPerVisitByService(businessId, serviceMoney.keySet());
        for (Map.Entry<String, BigDecimal[]> entry : serviceMoney.entrySet()) {
            long[] c = serviceCounts.getOrDefault(entry.getKey(), new long[2]);
            BigDecimal[] m = entry.getValue();
            String name = serviceNames.get(entry.getKey());
            BigDecimal products = costPerVisit.getOrDefault(entry.getKey(), ZERO).multiply(BigDecimal.valueOf(c[0]));
            serviceRows.add(new ServiceReportRow(entry.getKey(), name == null ? "Service" : name,
                    c[0], money(m[0]), money(m[1]), money(products),
                    money(m[0].subtract(m[1]).subtract(products)), c[1]));
            revenue = revenue.add(m[0]);
            commission = commission.add(m[1]);
            productCost = productCost.add(products);
        }
        serviceRows.sort(Comparator.comparing(ServiceReportRow::contribution).reversed());

        java.util.Set<String> staffIds = new java.util.LinkedHashSet<>(staffMoney.keySet());
        staffIds.addAll(staffCounts.keySet());
        Map<String, String> names = staffProfileService.displayNamesByIds(businessId, staffIds);
        List<StaffReportRow> staffRows = new ArrayList<>();
        for (String staffId : staffIds) {
            long[] c = staffCounts.getOrDefault(staffId, new long[3]);
            BigDecimal[] m = staffMoney.getOrDefault(staffId, new BigDecimal[] { ZERO, ZERO, ZERO });
            staffRows.add(new StaffReportRow(staffId, names.getOrDefault(staffId, "Staff"),
                    c[0], money(m[0]), money(m[1]), money(m[2]), c[1], c[2]));
        }
        staffRows.sort(Comparator.comparing(StaffReportRow::revenue).reversed());
        return new ServiceReportResponse(from, to, branch, serviceRows, staffRows,
                money(revenue), money(commission), money(productCost),
                money(revenue.subtract(commission).subtract(productCost)));
    }

    /**
     * Texts a staff member what they earned today and what the shop owes them. Uses the shop's
     * SMS credits. The number comes from their staff profile.
     */
    @Transactional(readOnly = true)
    public EarningsSmsResponse sendEarningsSms(String businessId, String staffProfileId) {
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        String digits = StkPhoneNormalizer.normalize(profile.getPhone());
        if (digits == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add a mobile number to this staff member first");
        }
        LocalDate today = LocalDate.now(NAIROBI);
        StatementResponse statement = staffEarningsService.statement(businessId, today, today);
        StatementRow row = statement.rows().stream()
                .filter(r -> staffProfileId.equals(r.staffProfileId()))
                .findFirst()
                .orElse(null);
        BigDecimal commission = row == null ? ZERO : row.commission().add(row.reversals());
        BigDecimal tips = row == null ? ZERO : row.tips();
        BigDecimal owed = staffEarningsService.balance(businessId, staffProfileId);
        String shop = businessRepository.findById(businessId)
                .map(b -> b.getName() == null ? null : b.getName().trim())
                .filter(n -> !n.isEmpty())
                .orElse("The shop");
        String name = staffProfileService.displayNamesByIds(businessId, List.of(staffProfileId))
                .getOrDefault(staffProfileId, "");
        String first = name.isBlank() ? "" : name.trim().split("\\s+")[0] + ", ";
        String message = shop + ": " + first + "today you earned KES " + plain(commission) + " commission"
                + (tips.signum() != 0 ? " and KES " + plain(tips) + " tips" : "")
                + ". You are owed KES " + plain(owed) + ".";
        TenantMessagingConfig config = messagingSettingsService.resolveForDispatch(businessId, SmsSendReason.PAYROLL);
        SmsMessagingClient.SendResult result = smsMessagingClient.sendText(config, "+" + digits, message);
        return new EarningsSmsResponse(result.sent() || result.stub(), mask(digits), message);
    }

    private static String mask(String digits) {
        return digits.length() <= 4 ? "****" : "*".repeat(digits.length() - 3) + digits.substring(digits.length() - 3);
    }

    private static String plain(BigDecimal amount) {
        return money(amount).stripTrailingZeros().scale() <= 0
                ? money(amount).setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : money(amount).toPlainString();
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }
}
