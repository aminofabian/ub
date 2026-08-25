package zelisline.ub.platform.overview;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.KioskPayAccountStatuses;
import zelisline.ub.payments.repository.KioskPayAccountRepository;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.BestSellerRow;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.CommercePulse;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.DayBucket;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.HotTenantRow;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.RecentTenantRow;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.StorefrontPulse;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.StuckRow;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.StuckSignups;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.SupportPulse;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse.TenantFleet;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.SaEmailRecipientResponse;
import zelisline.ub.platform.email.application.PlatformEmailAudienceService;
import zelisline.ub.platform.email.domain.PlatformEmailCampaign;
import zelisline.ub.support.domain.SupportConversation;
import zelisline.ub.support.repository.SupportConversationRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PlatformOverviewService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int STUCK_SAMPLE = 8;

    private final BusinessRepository businessRepository;
    private final PlatformOverviewRepository overviewRepository;
    private final PlatformEmailAudienceService audienceService;
    private final SupportConversationRepository supportConversationRepository;
    private final KioskPayAccountRepository kioskPayAccountRepository;

    @Transactional(readOnly = true)
    public PlatformOverviewResponse load() {
        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant since7d = now.minusSeconds(7L * 24 * 3600);
        Instant since14d = now.minusSeconds(14L * 24 * 3600);
        Instant since30d = now.minusSeconds(30L * 24 * 3600);

        long totalTenants = businessRepository.countByDeletedAtIsNull();
        long activeTenants = businessRepository.countByDeletedAtIsNullAndActiveTrue();
        long createdLast7 = businessRepository.countByDeletedAtIsNullAndCreatedAtGreaterThanEqual(since7d);
        long kioskPayActive = kioskPayAccountRepository.countByStatus(KioskPayAccountStatuses.ACTIVE);

        List<SaEmailRecipientResponse> stuck = audienceService.resolve(
                PlatformEmailCampaign.SEGMENT_STUCK_SIGNUP, null, null, null);
        List<StuckRow> stuckSample = stuck.stream()
                .limit(STUCK_SAMPLE)
                .map(r -> new StuckRow(
                        r.businessId(),
                        r.businessName(),
                        r.slug(),
                        r.email(),
                        r.name(),
                        r.onboardingStatus(),
                        r.continueKind(),
                        r.lastLoginAt() == null ? null : r.lastLoginAt().toString()
                ))
                .toList();

        Object[] todaySales = firstRow(overviewRepository.salesAggregateBetween(startOfToday, now));
        Object[] monthSales = firstRow(overviewRepository.salesAggregateBetween(since30d, now));
        Object[] allSales = firstRow(overviewRepository.salesAggregateAllTime());

        BigDecimal unitsToday = nz(overviewRepository.unitsSoldBetween(startOfToday, now));
        BigDecimal units30 = nz(overviewRepository.unitsSoldBetween(since30d, now));
        BigDecimal unitsAll = nz(overviewRepository.unitsSoldAllTime());

        Object[] sf30 = firstRow(overviewRepository.storefrontPaidBetween(since30d, now));
        Object[] sfAll = firstRow(overviewRepository.storefrontPaidAllTime());
        BigDecimal sfUnits30 = nz(overviewRepository.storefrontUnitsBetween(since30d, now));

        long openTenant = supportConversationRepository.countByStatusAndConversationType(
                SupportConversation.STATUS_OPEN, SupportConversation.TYPE_TENANT);
        long openVisitor = supportConversationRepository.countByStatusAndConversationType(
                SupportConversation.STATUS_OPEN, SupportConversation.TYPE_VISITOR);
        long waiting = supportConversationRepository.countWaitingOnAdmin(
                SupportConversation.STATUS_OPEN,
                List.of(SupportConversation.TYPE_TENANT, SupportConversation.TYPE_VISITOR));

        List<BestSellerRow> bestSellers = overviewRepository.topSellersSince(since30d)
                .stream()
                .map(row -> new BestSellerRow(
                        str(row[0]),
                        str(row[1]),
                        str(row[2]),
                        str(row[3]),
                        toBd(row[4]),
                        toBd(row[5]),
                        toLong(row[6])
                ))
                .toList();

        List<HotTenantRow> hotTenants = overviewRepository.hotTenantsSince(since7d)
                .stream()
                .map(row -> new HotTenantRow(
                        str(row[0]),
                        str(row[1]),
                        str(row[2]),
                        toLong(row[3]),
                        toBd(row[4]),
                        toBd(row[5])
                ))
                .toList();

        Map<LocalDate, DayBucket> byDay = new HashMap<>();
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        for (int i = 13; i >= 0; i--) {
            LocalDate d = todayUtc.minusDays(i);
            byDay.put(d, new DayBucket(DAY.format(d), 0L, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        for (Object[] row : overviewRepository.dailySalesSince(since14d)) {
            LocalDate d = toLocalDate(row[0]);
            if (d == null || !byDay.containsKey(d)) {
                continue;
            }
            byDay.put(d, new DayBucket(DAY.format(d), toLong(row[1]), toBd(row[2]), toBd(row[3])));
        }
        List<DayBucket> last14 = new ArrayList<>(14);
        for (int i = 13; i >= 0; i--) {
            last14.add(byDay.get(todayUtc.minusDays(i)));
        }

        List<RecentTenantRow> recent = businessRepository.findTop12ByDeletedAtIsNullOrderByCreatedAtDesc()
                .stream()
                .map(this::toRecent)
                .toList();

        return new PlatformOverviewResponse(
                new TenantFleet(
                        totalTenants,
                        activeTenants,
                        Math.max(0, totalTenants - activeTenants),
                        createdLast7,
                        kioskPayActive
                ),
                new StuckSignups(stuck.size(), stuckSample),
                new CommercePulse(
                        toLong(todaySales[0]),
                        toBd(todaySales[1]),
                        unitsToday,
                        toLong(monthSales[0]),
                        toBd(monthSales[1]),
                        units30,
                        unitsAll,
                        toLong(allSales[0]),
                        toBd(allSales[1])
                ),
                new StorefrontPulse(
                        toLong(sf30[0]),
                        toBd(sf30[1]),
                        sfUnits30,
                        toLong(sfAll[0]),
                        toBd(sfAll[1])
                ),
                new SupportPulse(openTenant, openVisitor, waiting),
                bestSellers,
                hotTenants,
                last14,
                recent
        );
    }

    private RecentTenantRow toRecent(Business b) {
        return new RecentTenantRow(
                b.getId(),
                b.getName(),
                b.getSlug(),
                b.isActive(),
                b.getSubscriptionTier() == null ? "" : b.getSubscriptionTier(),
                b.getCreatedAt() == null ? null : b.getCreatedAt().toString()
        );
    }

    private static Object[] firstRow(List<Object[]> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return new Object[] {0L, BigDecimal.ZERO};
        }
        Object first = rows.get(0);
        if (first instanceof Object[] arr) {
            return arr.length >= 2 ? arr : new Object[] {arr.length > 0 ? arr[0] : 0L, BigDecimal.ZERO};
        }
        return new Object[] {first, rows.size() > 1 ? rows.get(1) : BigDecimal.ZERO};
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal toBd(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static long toLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String str(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private static LocalDate toLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof java.util.Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        try {
            String s = raw.toString();
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception ex) {
            return null;
        }
    }
}
