package zelisline.ub.credits.email.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceFilter;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceRecipientRow;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.FilterCondition;
import zelisline.ub.credits.email.domain.CustomerEmailCampaign;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.application.FrontendAuthLinkBuilder;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.storefront.application.ShopperPhoneEmails;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class CustomerEmailAudienceService {

    public static final String SKIP_SOFT_DELETED = "soft_deleted";
    public static final String SKIP_ANONYMISED = "anonymised";
    public static final String SKIP_MISSING_EMAIL = "missing_email";
    public static final String SKIP_INVALID_EMAIL = "invalid_email";
    public static final String SKIP_SYNTHETIC_EMAIL = "synthetic_email";

    private static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");
    private static final int SAMPLE_LIMIT = 40;

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final BusinessRepository businessRepository;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;
    private final ObjectMapper objectMapper;

    public record ResolvedAudience(
            List<AudienceRecipientRow> matched,
            List<AudienceRecipientRow> excluded,
            List<AudienceRecipientRow> eligible
    ) {
        public int matchedCount() {
            return matched.size();
        }

        public int excludedCount() {
            return excluded.size();
        }

        public int finalCount() {
            return eligible.size();
        }
    }

    public record PurchaseStats(
            long purchaseCount,
            BigDecimal totalAmount,
            Instant firstPurchaseAt,
            Instant lastPurchaseAt
    ) {
        static PurchaseStats empty() {
            return new PurchaseStats(0, BigDecimal.ZERO, null, null);
        }
    }

    public String normalizeMethod(String method) {
        String m = method == null ? "" : method.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case CustomerEmailCampaign.METHOD_SPECIFIC,
                    CustomerEmailCampaign.METHOD_FILTERED,
                    CustomerEmailCampaign.METHOD_ALL_ELIGIBLE -> m;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown recipient method: " + method);
        };
    }

    public String serializeFilter(AudienceFilter filter) {
        if (filter == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filter payload");
        }
    }

    public AudienceFilter deserializeFilter(String json) {
        if (json == null || json.isBlank()) {
            return new AudienceFilter("ALL", List.of());
        }
        try {
            return objectMapper.readValue(json, AudienceFilter.class);
        } catch (JsonProcessingException ex) {
            return new AudienceFilter("ALL", List.of());
        }
    }

    public ResolvedAudience resolve(
            String businessId,
            String recipientMethod,
            List<String> customerIds,
            AudienceFilter filter
    ) {
        String method = normalizeMethod(recipientMethod);
        List<Customer> candidates = switch (method) {
            case CustomerEmailCampaign.METHOD_SPECIFIC -> loadSpecific(businessId, customerIds);
            case CustomerEmailCampaign.METHOD_FILTERED -> applyFilters(
                    loadActiveCandidates(businessId), businessId, filter);
            case CustomerEmailCampaign.METHOD_ALL_ELIGIBLE -> loadActiveCandidates(businessId);
            default -> List.of();
        };

        Map<String, CustomerPhone> primaryPhone = primaryPhones(candidates);
        List<AudienceRecipientRow> matched = new ArrayList<>();
        List<AudienceRecipientRow> excluded = new ArrayList<>();
        List<AudienceRecipientRow> eligible = new ArrayList<>();

        for (Customer customer : candidates) {
            String phone = phoneDisplay(primaryPhone.get(customer.getId()));
            String skip = eligibilitySkipReason(customer);
            AudienceRecipientRow row = new AudienceRecipientRow(
                    customer.getId(),
                    displayName(customer),
                    customer.getEmail(),
                    phone,
                    skip);
            matched.add(row);
            if (skip != null) {
                excluded.add(row);
            } else {
                eligible.add(row);
            }
        }
        return new ResolvedAudience(matched, excluded, eligible);
    }

    public List<AudienceRecipientRow> sample(List<AudienceRecipientRow> rows) {
        if (rows.size() <= SAMPLE_LIMIT) {
            return rows;
        }
        return rows.subList(0, SAMPLE_LIMIT);
    }

    public String shopName(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("our shop");
    }

    public String shopOrigin(String businessId) {
        return frontendAuthLinkBuilder.tenantOrigin(businessId);
    }

    public static String skipReasonForEmail(String email) {
        if (email == null || email.isBlank()) {
            return SKIP_MISSING_EMAIL;
        }
        String trimmed = email.trim();
        if (ShopperPhoneEmails.isSynthetic(trimmed)) {
            return SKIP_SYNTHETIC_EMAIL;
        }
        if (!trimmed.contains("@") || trimmed.startsWith("@") || trimmed.endsWith("@")) {
            return SKIP_INVALID_EMAIL;
        }
        return null;
    }

    public String eligibilitySkipReason(Customer customer) {
        if (customer.getDeletedAt() != null) {
            return SKIP_SOFT_DELETED;
        }
        if (customer.getAnonymisedAt() != null) {
            return SKIP_ANONYMISED;
        }
        return skipReasonForEmail(customer.getEmail());
    }

    private List<Customer> loadSpecific(String businessId, List<String> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one customer");
        }
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        for (String id : customerIds) {
            if (id != null && !id.isBlank()) {
                ordered.putIfAbsent(id.trim(), Boolean.TRUE);
            }
        }
        if (ordered.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one customer");
        }
        if (ordered.size() > CustomerEmailCampaign.MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Too many recipients (max " + CustomerEmailCampaign.MAX_RECIPIENTS + ")");
        }
        List<Customer> found = customerRepository.findByIdInAndBusinessId(
                ordered.keySet(), businessId);
        Map<String, Customer> byId = new HashMap<>();
        for (Customer c : found) {
            byId.put(c.getId(), c);
        }
        List<Customer> out = new ArrayList<>();
        for (String id : ordered.keySet()) {
            Customer c = byId.get(id);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private List<Customer> loadActiveCandidates(String businessId) {
        return customerRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .filter(c -> c.getAnonymisedAt() == null)
                .toList();
    }

    private List<Customer> applyFilters(List<Customer> base, String businessId, AudienceFilter filter) {
        if (filter == null || filter.conditions() == null || filter.conditions().isEmpty()) {
            return base;
        }
        boolean any = "ANY".equalsIgnoreCase(filter.matchMode() == null ? "" : filter.matchMode());
        Map<String, CreditAccount> credits = creditsByCustomer(base);
        Map<String, List<CustomerPhone>> phones = phonesByCustomer(base);
        Map<String, PurchaseStats> purchases = purchaseStats(businessId);
        Set<String> boughtProductIds = boughtProductCustomerIds(businessId, filter.conditions());

        List<Customer> out = new ArrayList<>();
        for (Customer customer : base) {
            boolean match = any ? false : true;
            for (FilterCondition condition : filter.conditions()) {
                boolean ok = evaluate(
                        customer,
                        condition,
                        credits.get(customer.getId()),
                        phones.getOrDefault(customer.getId(), List.of()),
                        purchases.getOrDefault(customer.getId(), PurchaseStats.empty()),
                        boughtProductIds);
                if (any) {
                    if (ok) {
                        match = true;
                        break;
                    }
                } else if (!ok) {
                    match = false;
                    break;
                }
            }
            if (match) {
                out.add(customer);
            }
        }
        return out;
    }

    private boolean evaluate(
            Customer customer,
            FilterCondition condition,
            CreditAccount credit,
            List<CustomerPhone> phones,
            PurchaseStats purchases,
            Set<String> boughtProductIds
    ) {
        String field = condition.field() == null ? "" : condition.field().trim().toLowerCase(Locale.ROOT);
        String op = condition.op() == null ? "" : condition.op().trim().toLowerCase(Locale.ROOT);
        return switch (field) {
            case "customer_status", "status" -> matchStatus(customer, op, condition.value());
            case "origin" -> eqIgnoreCase(customer.getOrigin(), condition.value());
            case "has_email" -> {
                boolean has = customer.getEmail() != null && !customer.getEmail().isBlank()
                        && skipReasonForEmail(customer.getEmail()) == null;
                yield boolValue(condition.value()) == has;
            }
            case "phone_verification", "phone_verified" -> {
                boolean verified = phones.stream().anyMatch(p -> p.getVerifiedAt() != null);
                yield boolValue(condition.value()) == verified
                        || eqIgnoreCase(condition.value(), verified ? "verified" : "not_verified");
            }
            case "credit_status" -> matchCreditStatus(credit, condition.value());
            case "created_date", "registration_date" ->
                    matchInstant(customer.getCreatedAt(), op, condition);
            case "first_purchase" -> matchFirstPurchase(purchases, condition.value());
            case "last_purchase_date" -> matchLastPurchase(purchases, op, condition);
            case "total_purchase_amount" ->
                    matchDecimal(purchases.totalAmount(), op, condition);
            case "number_of_purchases", "purchase_count" ->
                    matchLong(purchases.purchaseCount(), op, condition);
            case "tab_balance", "balance_owed" ->
                    matchDecimal(credit == null ? BigDecimal.ZERO : credit.getBalanceOwed(), op, condition);
            case "wallet_balance" ->
                    matchDecimal(credit == null ? BigDecimal.ZERO : credit.getWalletBalance(), op, condition);
            case "loyalty_points" ->
                    matchLong(credit == null ? 0 : credit.getLoyaltyPoints(), op, condition);
            case "bought_product" -> {
                String itemId = condition.itemId() != null ? condition.itemId() : condition.value();
                yield itemId != null && !itemId.isBlank() && boughtProductIds.contains(customer.getId());
            }
            case "marketing_eligibility" -> {
                // Until preference centre ships: eligible ≈ usable email + not excluded structurally.
                boolean eligible = eligibilitySkipReason(customer) == null;
                yield eqIgnoreCase(condition.value(), eligible ? "eligible" : "not_eligible");
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unsupported filter field: " + condition.field());
        };
    }

    private static boolean matchStatus(Customer customer, String op, String value) {
        String status;
        if (customer.getAnonymisedAt() != null) {
            status = "anonymised";
        } else if (customer.getDeletedAt() != null) {
            status = "soft_deleted";
        } else {
            status = "active";
        }
        return eqIgnoreCase(status, value) || eqIgnoreCase(status, normalizeStatusAlias(value));
    }

    private static String normalizeStatusAlias(String value) {
        if (value == null) {
            return "";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "inactive", "deleted" -> "soft_deleted";
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static boolean matchCreditStatus(CreditAccount credit, String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        boolean suspended = credit != null && credit.isCreditSuspended();
        BigDecimal owed = credit == null || credit.getBalanceOwed() == null
                ? BigDecimal.ZERO
                : credit.getBalanceOwed();
        return switch (v) {
            case "suspended" -> suspended;
            case "on_tab", "on tab" -> !suspended && owed.compareTo(BigDecimal.ZERO) > 0;
            case "clear" -> !suspended && owed.compareTo(BigDecimal.ZERO) <= 0;
            default -> false;
        };
    }

    private static boolean matchFirstPurchase(PurchaseStats stats, String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        boolean completed = stats.purchaseCount() > 0;
        return switch (v) {
            case "completed", "yes", "true" -> completed;
            case "not_completed", "not completed", "no", "false" -> !completed;
            default -> false;
        };
    }

    private static boolean matchLastPurchase(PurchaseStats stats, String op, FilterCondition condition) {
        if ("never".equalsIgnoreCase(op) || "never".equalsIgnoreCase(condition.value())) {
            return stats.lastPurchaseAt() == null;
        }
        if (stats.lastPurchaseAt() == null) {
            return false;
        }
        return matchInstant(stats.lastPurchaseAt(), op, condition);
    }

    private static boolean matchInstant(Instant instant, String op, FilterCondition condition) {
        if (instant == null) {
            return false;
        }
        LocalDate day = LocalDate.ofInstant(instant, ZONE);
        return switch (op) {
            case "before" -> {
                LocalDate bound = parseDate(condition.value());
                yield bound != null && day.isBefore(bound);
            }
            case "after" -> {
                LocalDate bound = parseDate(condition.value());
                yield bound != null && day.isAfter(bound);
            }
            case "between" -> {
                LocalDate from = parseDate(condition.value());
                LocalDate to = parseDate(condition.valueTo());
                yield from != null && to != null && !day.isBefore(from) && !day.isAfter(to);
            }
            case "last_x_days", "last_days" -> {
                int days = condition.days() != null
                        ? condition.days()
                        : parseInt(condition.value(), 0);
                yield days > 0 && !instant.isBefore(Instant.now().minus(days, ChronoUnit.DAYS));
            }
            default -> false;
        };
    }

    private static boolean matchDecimal(BigDecimal actual, String op, FilterCondition condition) {
        BigDecimal a = actual == null ? BigDecimal.ZERO : actual;
        return switch (op) {
            case "gt", "greater_than", ">" -> {
                BigDecimal v = parseDecimal(condition.value());
                yield v != null && a.compareTo(v) > 0;
            }
            case "lt", "less_than", "<" -> {
                BigDecimal v = parseDecimal(condition.value());
                yield v != null && a.compareTo(v) < 0;
            }
            case "eq", "equal_to", "=" -> {
                BigDecimal v = parseDecimal(condition.value());
                yield v != null && a.compareTo(v) == 0;
            }
            case "between" -> {
                BigDecimal from = parseDecimal(condition.value());
                BigDecimal to = parseDecimal(condition.valueTo());
                yield from != null && to != null && a.compareTo(from) >= 0 && a.compareTo(to) <= 0;
            }
            default -> false;
        };
    }

    private static boolean matchLong(long actual, String op, FilterCondition condition) {
        return matchDecimal(BigDecimal.valueOf(actual), op, condition);
    }

    private Map<String, PurchaseStats> purchaseStats(String businessId) {
        List<Object[]> rows = saleRepository.aggregatePurchaseStatsByCustomer(businessId);
        Map<String, PurchaseStats> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row[0] == null) {
                continue;
            }
            String customerId = Objects.toString(row[0], null);
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            BigDecimal total = row[2] instanceof BigDecimal bd
                    ? bd
                    : row[2] instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
            Instant first = row[3] instanceof Instant i ? i : null;
            Instant last = row[4] instanceof Instant i ? i : null;
            out.put(customerId, new PurchaseStats(count, total, first, last));
        }
        return out;
    }

    private Set<String> boughtProductCustomerIds(String businessId, List<FilterCondition> conditions) {
        Set<String> out = new HashSet<>();
        for (FilterCondition condition : conditions) {
            if (condition == null || condition.field() == null) {
                continue;
            }
            if (!"bought_product".equalsIgnoreCase(condition.field().trim())) {
                continue;
            }
            String itemId = condition.itemId() != null ? condition.itemId() : condition.value();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            Instant from = null;
            Instant toExclusive = null;
            if (condition.value() != null && condition.valueTo() != null
                    && condition.itemId() != null) {
                LocalDate fromDay = parseDate(condition.value());
                LocalDate toDay = parseDate(condition.valueTo());
                if (fromDay != null) {
                    from = fromDay.atStartOfDay(ZONE).toInstant();
                }
                if (toDay != null) {
                    toExclusive = toDay.plusDays(1).atStartOfDay(ZONE).toInstant();
                }
            }
            List<Object[]> buyers = saleItemRepository.buyersOfItem(
                    businessId, List.of(itemId.trim()), PageRequest.of(0, 5000));
            for (Object[] row : buyers) {
                if (row != null && row[0] != null) {
                    // Optional date window is applied client-side on last purchase when provided.
                    if (from != null || toExclusive != null) {
                        Instant last = row[4] instanceof Instant i ? i : null;
                        if (last == null) {
                            continue;
                        }
                        if (from != null && last.isBefore(from)) {
                            continue;
                        }
                        if (toExclusive != null && !last.isBefore(toExclusive)) {
                            continue;
                        }
                    }
                    out.add(row[0].toString());
                }
            }
        }
        return out;
    }

    private Map<String, CreditAccount> creditsByCustomer(List<Customer> customers) {
        if (customers.isEmpty()) {
            return Map.of();
        }
        List<String> ids = customers.stream().map(Customer::getId).toList();
        Map<String, CreditAccount> out = new HashMap<>();
        for (CreditAccount account : creditAccountRepository.findByCustomerIdIn(ids)) {
            out.put(account.getCustomerId(), account);
        }
        return out;
    }

    private Map<String, List<CustomerPhone>> phonesByCustomer(List<Customer> customers) {
        if (customers.isEmpty()) {
            return Map.of();
        }
        List<String> ids = customers.stream().map(Customer::getId).toList();
        Map<String, List<CustomerPhone>> out = new HashMap<>();
        for (CustomerPhone phone : customerPhoneRepository.findByCustomerIdIn(ids)) {
            out.computeIfAbsent(phone.getCustomerId(), k -> new ArrayList<>()).add(phone);
        }
        return out;
    }

    private Map<String, CustomerPhone> primaryPhones(List<Customer> customers) {
        Map<String, List<CustomerPhone>> all = phonesByCustomer(customers);
        Map<String, CustomerPhone> out = new HashMap<>();
        for (Map.Entry<String, List<CustomerPhone>> e : all.entrySet()) {
            CustomerPhone primary = e.getValue().stream()
                    .filter(CustomerPhone::isPrimary)
                    .findFirst()
                    .orElse(e.getValue().isEmpty() ? null : e.getValue().get(0));
            if (primary != null) {
                out.put(e.getKey(), primary);
            }
        }
        return out;
    }

    private static String phoneDisplay(CustomerPhone phone) {
        if (phone == null) {
            return null;
        }
        if (phone.getPhone() != null && !phone.getPhone().isBlank()) {
            return phone.getPhone();
        }
        if (phone.getAssignedMsisdn() != null && !phone.getAssignedMsisdn().isBlank()) {
            return phone.getAssignedMsisdn();
        }
        return phone.getMaskedMsisdn();
    }

    private static String displayName(Customer customer) {
        if (customer.getName() != null && !customer.getName().isBlank()) {
            return customer.getName();
        }
        return "Customer";
    }

    private static boolean eqIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean boolValue(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "yes".equals(v) || "1".equals(v)
                || "has_email".equals(v) || "verified".equals(v) || "eligible".equals(v);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }
}
