package zelisline.ub.onboarding.sequence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingEnrollment;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class MerchantOnboardingGateService {

    private static final Pattern SIZE_TOKEN = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(ml|l|ltr|litre|liter|kg|g|gm|pcs|pc|pack|crate|dozen)\\b");

    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final SaleRepository saleRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessOnboardingSettingsService onboardingSettingsService;
    private final UserRepository userRepository;
    private final ShiftRepository shiftRepository;

    public record Snapshot(
            long sellableSkuCount,
            long catalogImportCount,
            long supplierCount,
            long saleCount,
            boolean hasPostedSupply,
            boolean hasCompletedSale,
            boolean hasLookalikeProducts,
            boolean questionnaireDone,
            String onboardingStatus,
            /** Questionnaire: {@code new}, {@code spreadsheet}, {@code other_pos}, or null. */
            String productSource,
            boolean nicheSpecialty,
            /** More than the owner alone ⇒ someone else was invited to the team. */
            boolean hasInvitedStaff,
            /** At least one shift has ever been closed. */
            boolean hasClosedShift,
            ZoneId zone
    ) {
        public boolean migrating() {
            return "spreadsheet".equalsIgnoreCase(productSource)
                    || "other_pos".equalsIgnoreCase(productSource);
        }
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(String businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        ZoneId zone = resolveZone(business);
        long sellable = itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrueAndSellableTrue(businessId);
        long catalog = itemRepository
                .countByBusinessIdAndDeletedAtIsNullAndActiveTrueAndGlobalProductSourceIdIsNotNull(businessId);
        long suppliers = supplierRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        boolean sale = saleRepository.existsByBusinessIdAndStatusAndVoidedAtIsNull(businessId, "completed");
        boolean supply = supplierInvoiceRepository.existsByBusinessIdAndStatus(
                businessId, PurchasingConstants.INVOICE_POSTED);
        long sales = sale ? 1L : 0L; // exact count not required for gates; week check-in uses sellable + suppliers
        if (sale) {
            // Prefer a real count when available via all-time aggregate shape; fall back to 1.
            try {
                List<Object[]> agg = saleRepository.aggregateSalesAllTime(businessId);
                if (agg != null && !agg.isEmpty() && agg.getFirst() != null && agg.getFirst().length > 0
                        && agg.getFirst()[0] instanceof Number n) {
                    sales = n.longValue();
                }
            } catch (RuntimeException ignored) {
                // keep sales = 1
            }
        }
        String onbStatus = "idle";
        String productSource = null;
        boolean nicheSpecialty = false;
        if (business != null) {
            var onb = onboardingSettingsService.readFromSettingsJson(business.getSettings());
            onbStatus = onb.status();
            if (onb.answers() != null) {
                productSource = onb.answers().productSource();
                nicheSpecialty = isNicheSpecialty(onb.answers().storeTypes());
            }
        }
        boolean questionnaireDone = "completed".equalsIgnoreCase(onbStatus)
                || "dismissed".equalsIgnoreCase(onbStatus);
        boolean lookalikes = detectLookalikes(businessId);
        long staff = userRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        boolean invitedStaff = staff > 1; // owner alone is one user
        boolean closedShift = shiftRepository.existsByBusinessIdAndStatus(
                businessId, SalesConstants.SHIFT_STATUS_CLOSED);
        return new Snapshot(
                sellable,
                catalog,
                suppliers,
                sales,
                supply,
                sale,
                lookalikes,
                questionnaireDone,
                onbStatus,
                productSource,
                nicheSpecialty,
                invitedStaff,
                closedShift,
                zone);
    }

    static boolean isNicheSpecialty(List<String> storeTypes) {
        if (storeTypes == null || storeTypes.isEmpty()) {
            return false;
        }
        for (String raw : storeTypes) {
            if (raw == null) {
                continue;
            }
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if ("butchery".equals(t) || "cosmetics".equals(t) || "wines-spirits".equals(t)) {
                return true;
            }
        }
        return false;
    }

    public void refreshMilestones(MerchantOnboardingEnrollment enrollment, Snapshot snap) {
        Instant now = Instant.now();
        if (enrollment.getFirstSellableAt() == null && snap.sellableSkuCount() > 0) {
            enrollment.setFirstSellableAt(now);
        }
        if (enrollment.getFirstSupplyAt() == null && snap.hasPostedSupply()) {
            enrollment.setFirstSupplyAt(now);
        }
        if (enrollment.getFirstSaleAt() == null && snap.hasCompletedSale()) {
            enrollment.setFirstSaleAt(now);
        }
    }

    /** Next local 09:00 at or after {@code after}, in the business timezone. */
    public Instant nextMorningNine(Instant after, ZoneId zone) {
        ZonedDateTime zdt = after.atZone(zone);
        LocalDate day = zdt.toLocalDate();
        ZonedDateTime nine = ZonedDateTime.of(day, LocalTime.of(9, 0), zone);
        if (!zdt.toLocalTime().isBefore(LocalTime.of(9, 0))) {
            nine = nine.plusDays(1);
        }
        return nine.toInstant();
    }

    /**
     * M1 due: enroll+4h, or next 09:00 if that lands in quiet hours (21:00–09:00).
     */
    public Instant m1DueAt(Instant enrolledAt, ZoneId zone) {
        Instant plus4 = enrolledAt.plusSeconds(4 * 3600L);
        ZonedDateTime local = plus4.atZone(zone);
        int hour = local.getHour();
        if (hour >= 21 || hour < 9) {
            return nextMorningNine(plus4, zone);
        }
        return plus4;
    }

    public Instant endOfLocalDay(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        return local.toLocalDate().atTime(20, 0).atZone(zone).toInstant();
    }

    boolean detectLookalikes(String businessId) {
        List<Item> items = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        if (items.size() < 2) {
            return false;
        }
        Map<String, List<String>> byStem = new HashMap<>();
        for (Item item : items) {
            if (!item.isActive() || item.getDeletedAt() != null) {
                continue;
            }
            String name = item.getName() == null ? "" : item.getName().trim();
            if (name.isBlank()) {
                continue;
            }
            String stem = stem(name);
            if (stem.length() < 3) {
                continue;
            }
            byStem.computeIfAbsent(stem, k -> new ArrayList<>()).add(name);
        }
        for (List<String> group : byStem.values()) {
            if (group.size() < 2) {
                continue;
            }
            Set<String> sizes = new HashSet<>();
            int withSize = 0;
            for (String name : group) {
                Matcher m = SIZE_TOKEN.matcher(name);
                if (m.find()) {
                    withSize++;
                    sizes.add(m.group(1) + m.group(2).toLowerCase(Locale.ROOT));
                }
            }
            if (withSize >= 2 && sizes.size() >= 2) {
                return true;
            }
        }
        return false;
    }

    static String stem(String name) {
        String cleaned = SIZE_TOKEN.matcher(name).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return cleaned.toLowerCase(Locale.ROOT);
    }

    static ZoneId resolveZone(Business business) {
        if (business == null || business.getTimezone() == null || business.getTimezone().isBlank()) {
            return ZoneId.of("Africa/Nairobi");
        }
        try {
            return ZoneId.of(business.getTimezone().trim());
        } catch (RuntimeException ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }
}
