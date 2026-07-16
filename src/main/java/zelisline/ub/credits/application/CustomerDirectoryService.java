package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.credits.api.dto.AddCustomerPhoneRequest;
import zelisline.ub.credits.api.dto.CreateCustomerRequest;
import zelisline.ub.credits.api.dto.CreditAccountSummaryResponse;
import zelisline.ub.credits.api.dto.CustomerPhoneDraft;
import zelisline.ub.credits.api.dto.CustomerPhoneResponse;
import zelisline.ub.credits.api.dto.CustomerResponse;
import zelisline.ub.credits.api.dto.OutstandingTabRowResponse;
import zelisline.ub.credits.api.dto.PatchCustomerRequest;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;

@Service
@RequiredArgsConstructor
public class CustomerDirectoryService {

    private static final Pattern UUID_LIKE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(String businessId, String phoneQuery, Pageable pageable) {
        if (phoneQuery != null && !phoneQuery.isBlank()) {
            String normalized = CustomerPhoneNormalizer.normalize(phoneQuery);
            if (normalized.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone search requires digits");
            }
        }
        Page<Customer> page = resolveListPage(businessId, phoneQuery, pageable);
        List<Customer> rows = page.getContent();
        Map<String, List<CustomerPhone>> phonesByCustomer = phonesGroupedByCustomer(rows);
        Map<String, CreditAccount> creditByCustomer = creditByCustomer(rows);
        List<CustomerResponse> mapped = rows.stream()
                .map(c -> assembleResponse(c, phonesByCustomer.get(c.getId()), creditByCustomer.get(c.getId())))
                .toList();
        return new PageImpl<>(mapped, page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<OutstandingTabRowResponse> listOutstandingTabs(String businessId, String query) {
        List<CreditAccount> accounts = creditAccountRepository.findOutstandingByBusinessId(businessId);
        if (accounts.isEmpty()) {
            return List.of();
        }
        List<String> customerIds = accounts.stream().map(CreditAccount::getCustomerId).distinct().toList();
        Map<String, Customer> customers = new HashMap<>();
        for (Customer c : customerRepository.findAllById(customerIds)) {
            if (c.getDeletedAt() == null) {
                customers.put(c.getId(), c);
            }
        }
        Map<String, String> primaryPhone = new HashMap<>();
        for (CustomerPhone p : customerPhoneRepository.findByCustomerIdIn(customerIds)) {
            if (p.isPrimary() || !primaryPhone.containsKey(p.getCustomerId())) {
                primaryPhone.put(p.getCustomerId(), p.getPhone());
            }
        }
        String q = query == null ? "" : query.trim().toLowerCase();
        String qDigits = CustomerPhoneNormalizer.normalize(query == null ? "" : query);
        List<OutstandingTabRowResponse> out = new ArrayList<>();
        for (CreditAccount acc : accounts) {
            Customer c = customers.get(acc.getCustomerId());
            if (c == null) {
                continue;
            }
            String phone = primaryPhone.get(c.getId());
            if (!q.isEmpty()) {
                boolean nameHit = c.getName() != null && c.getName().toLowerCase().contains(q);
                boolean phoneHit = phone != null && !qDigits.isEmpty() && phone.contains(qDigits);
                if (!nameHit && !phoneHit) {
                    continue;
                }
            }
            out.add(new OutstandingTabRowResponse(
                    c.getId(),
                    c.getName(),
                    phone,
                    acc.getBalanceOwed()
            ));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(String businessId, String customerId) {
        Customer customer = loadActive(businessId, customerId);
        return toResponseSingle(businessId, customer);
    }

    /**
     * Resolve a customer id from either:
     * - a UUID customer id, or
     * - a phone string (normalized to digits) that must match exactly one active customer in the tenant.
     *
     * Returns 404 if not found; 409 if the phone matches multiple customers.
     */
    @Transactional(readOnly = true)
    public String resolveCustomerIdOrThrow(String businessId, String idOrPhone) {
        String key = idOrPhone == null ? "" : idOrPhone.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer identifier required");
        }
        if (UUID_LIKE.matcher(key).matches()) {
            // Ensure it exists and belongs to this tenant.
            loadActive(businessId, key);
            return key;
        }
        String normalized = CustomerPhoneNormalizer.normalize(key);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone search requires digits");
        }
        Page<Customer> page = customerRepository.findByBusinessIdAndPhoneNormalized(
                businessId, normalized, PageRequest.of(0, 2));
        List<Customer> rows = page.getContent();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        if (rows.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone matches multiple customers");
        }
        return rows.getFirst().getId();
    }

    @Transactional
    public CustomerResponse create(String businessId, CreateCustomerRequest request) {
        return create(businessId, request, null);
    }

    @Transactional
    public CustomerResponse create(String businessId, CreateCustomerRequest request, String actorUserId) {
        List<CustomerPhoneDraft> drafts = normalizedPrimaryDrafts(request.phones());
        assertDistinctNormalizedInRequest(drafts);
        for (CustomerPhoneDraft d : drafts) {
            assertPhoneAvailable(businessId, d.phone());
        }
        Customer customer = new Customer();
        customer.setBusinessId(businessId);
        customer.setName(request.name().trim());
        customer.setEmail(blankToNull(request.email()));
        customer.setNotes(blankToNull(request.notes()));
        customerRepository.save(customer);

        persistPhonesFromDrafts(businessId, customer.getId(), drafts);
        openCreditAccount(businessId, customer.getId(), request.creditLimit());
        publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_CREATED, null);
        if (request.creditLimit() != null && request.creditLimit().signum() >= 0) {
            publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_CREDIT_LIMIT_CHANGED,
                    map("creditLimit", map("old", null, "new", request.creditLimit().toPlainString())));
        }
        return toResponseSingle(businessId, customer);
    }

    @Transactional
    public CustomerResponse patch(String businessId, String customerId, PatchCustomerRequest patch) {
        return patch(businessId, customerId, patch, null);
    }

    @Transactional
    public CustomerResponse patch(String businessId, String customerId, PatchCustomerRequest patch, String actorUserId) {
        Customer customer = loadActive(businessId, customerId);
        Map<String, Object> oldState = customerSnapshot(customer);
        BigDecimal oldCreditLimit = null;
        if (patch.creditLimit() != null) {
            CreditAccount account = creditAccountRepository
                    .findByCustomerIdAndBusinessId(customerId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
            oldCreditLimit = account.getCreditLimit();
            if (patch.creditAccountVersion() != null && patch.creditAccountVersion() != account.getVersion()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale credit account version");
            }
            account.setCreditLimit(patch.creditLimit());
            creditAccountRepository.save(account);
        }
        if (patch.version() != null && patch.version() != customer.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale customer version");
        }
        if (patch.name() != null && !patch.name().isBlank()) {
            customer.setName(patch.name().trim());
        }
        if (patch.email() != null) {
            customer.setEmail(blankToNull(patch.email()));
        }
        if (patch.notes() != null) {
            customer.setNotes(blankToNull(patch.notes()));
        }
        customerRepository.save(customer);

        Map<String, Object> newState = customerSnapshot(customer);
        Map<String, Object> diff = compactDiff(oldState, newState);
        if (!diff.isEmpty()) {
            publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_UPDATED, diff);
        }
        if (patch.creditLimit() != null && oldCreditLimit != null
                && oldCreditLimit.compareTo(patch.creditLimit()) != 0) {
            publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_CREDIT_LIMIT_CHANGED,
                    map("creditLimit", map(
                            "old", oldCreditLimit.toPlainString(),
                            "new", patch.creditLimit().toPlainString())));
        }
        return toResponseSingle(businessId, customer);
    }

    @Transactional
    public void softDelete(String businessId, String customerId) {
        softDelete(businessId, customerId, null);
    }

    @Transactional
    public void softDelete(String businessId, String customerId, String actorUserId) {
        Customer customer = loadActive(businessId, customerId);
        customer.setDeletedAt(Instant.now());
        customerRepository.save(customer);
        customerPhoneRepository.deleteByCustomerId(customerId);
        publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_DELETED, null);
    }

    @Transactional
    public CustomerResponse addPhone(String businessId, String customerId, AddCustomerPhoneRequest request) {
        return addPhone(businessId, customerId, request, null);
    }

    @Transactional
    public CustomerResponse addPhone(String businessId, String customerId, AddCustomerPhoneRequest request, String actorUserId) {
        Customer customer = loadActive(businessId, customerId);
        String normalized = normalizedPhoneOrThrow(request.phone());
        assertPhoneAvailable(businessId, normalized);
        boolean wantsPrimary = Boolean.TRUE.equals(request.primary());
        if (wantsPrimary) {
            clearPrimary(customerId);
        }
        CustomerPhone row = new CustomerPhone();
        row.setBusinessId(businessId);
        row.setCustomerId(customerId);
        row.setPhone(normalized);
        boolean primary = wantsPrimary || !anyPrimary(customerId);
        row.setPrimary(primary);
        customerPhoneRepository.save(row);
        if (primary) {
            demoteOtherPrimaries(customerId, row.getId());
        }
        publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_UPDATED,
                map("phoneAdded", map("phone", normalized, "primary", primary)));
        return toResponseSingle(businessId, customerRepository.findById(customerId).orElseThrow());
    }

    @Transactional
    public CustomerResponse setPrimaryPhone(String businessId, String customerId, String phoneId) {
        return setPrimaryPhone(businessId, customerId, phoneId, null);
    }

    @Transactional
    public CustomerResponse setPrimaryPhone(String businessId, String customerId, String phoneId, String actorUserId) {
        Customer customer = loadActive(businessId, customerId);
        CustomerPhone row = customerPhoneRepository.findById(phoneId)
                .filter(p -> businessId.equals(p.getBusinessId()) && customerId.equals(p.getCustomerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Phone not found"));
        clearPrimary(customerId);
        row.setPrimary(true);
        customerPhoneRepository.save(row);
        publishCustomerEvent(businessId, customer, actorUserId, AuditEventTypes.CUSTOMER_UPDATED,
                map("primaryPhone", map("phoneId", phoneId, "phone", row.getPhone())));
        return toResponseSingle(businessId, customerRepository.findById(customerId).orElseThrow());
    }

    private Page<Customer> resolveListPage(String businessId, String phoneRaw, Pageable pageable) {
        if (phoneRaw == null || phoneRaw.isBlank()) {
            return customerRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId, pageable);
        }
        String normalized = CustomerPhoneNormalizer.normalize(phoneRaw);
        return customerRepository.findByBusinessIdAndPhoneNormalized(businessId, normalized, pageable);
    }

    private Customer loadActive(String businessId, String customerId) {
        return customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    }

    private CustomerResponse toResponseSingle(String businessId, Customer customer) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId());
        CreditAccount acc = creditAccountRepository
                .findByCustomerIdAndBusinessId(customer.getId(), businessId)
                .orElse(null);
        return assembleResponse(customer, phones, acc);
    }

    private Map<String, List<CustomerPhone>> phonesGroupedByCustomer(List<Customer> customers) {
        if (customers.isEmpty()) {
            return Map.of();
        }
        List<String> ids = customers.stream().map(Customer::getId).toList();
        List<CustomerPhone> all = customerPhoneRepository.findByCustomerIdIn(ids);
        Map<String, List<CustomerPhone>> map = new HashMap<>();
        for (CustomerPhone p : all) {
            map.computeIfAbsent(p.getCustomerId(), k -> new ArrayList<>()).add(p);
        }
        return map;
    }

    private Map<String, CreditAccount> creditByCustomer(List<Customer> customers) {
        if (customers.isEmpty()) {
            return Map.of();
        }
        List<String> ids = customers.stream().map(Customer::getId).toList();
        return creditAccountRepository.findByCustomerIdIn(ids).stream()
                .collect(Collectors.toMap(CreditAccount::getCustomerId, a -> a, (a, b) -> a));
    }

    private CustomerResponse assembleResponse(Customer c, List<CustomerPhone> phones, CreditAccount acc) {
        List<CustomerPhoneResponse> phoneResponses = Optional.ofNullable(phones).orElse(List.of()).stream()
                .map(p -> new CustomerPhoneResponse(p.getId(), p.getPhone(), p.isPrimary(), p.getCreatedAt()))
                .toList();
        return new CustomerResponse(
                c.getId(),
                c.getName(),
                c.getEmail(),
                c.getNotes(),
                c.getVersion(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                phoneResponses,
                toCreditSummary(acc)
        );
    }

    private static CreditAccountSummaryResponse toCreditSummary(CreditAccount a) {
        if (a == null) {
            return new CreditAccountSummaryResponse(BigDecimal.ZERO, BigDecimal.ZERO, 0, null, 0);
        }
        return new CreditAccountSummaryResponse(
                a.getBalanceOwed(),
                a.getWalletBalance(),
                a.getLoyaltyPoints(),
                a.getCreditLimit(),
                a.getVersion()
        );
    }

    private void openCreditAccount(String businessId, String customerId, BigDecimal creditLimit) {
        CreditAccount row = new CreditAccount();
        row.setBusinessId(businessId);
        row.setCustomerId(customerId);
        row.setCreditLimit(creditLimit);
        creditAccountRepository.save(row);
    }

    private void persistPhonesFromDrafts(String businessId, String customerId, List<CustomerPhoneDraft> drafts) {
        for (CustomerPhoneDraft d : drafts) {
            String normalized = normalizedPhoneOrThrow(d.phone());
            CustomerPhone row = new CustomerPhone();
            row.setBusinessId(businessId);
            row.setCustomerId(customerId);
            row.setPhone(normalized);
            row.setPrimary(Boolean.TRUE.equals(d.primary()));
            customerPhoneRepository.save(row);
        }
        ensureSinglePrimary(customerId);
    }

    private void ensureSinglePrimary(String customerId) {
        List<CustomerPhone> list = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        long prim = list.stream().filter(CustomerPhone::isPrimary).count();
        if (prim == 0 && !list.isEmpty()) {
            CustomerPhone first = list.getFirst();
            first.setPrimary(true);
            customerPhoneRepository.save(first);
            demoteOtherPrimaries(customerId, first.getId());
            return;
        }
        if (prim > 1) {
            String winner = list.stream().filter(CustomerPhone::isPrimary).findFirst().orElseThrow().getId();
            demoteOtherPrimaries(customerId, winner);
        }
    }

    private void demoteOtherPrimaries(String customerId, String winnerId) {
        List<CustomerPhone> list = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        boolean dirty = false;
        for (CustomerPhone p : list) {
            if (p.isPrimary() && !p.getId().equals(winnerId)) {
                p.setPrimary(false);
                dirty = true;
            }
        }
        if (dirty) {
            customerPhoneRepository.saveAll(list);
        }
    }

    private void clearPrimary(String customerId) {
        List<CustomerPhone> list = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        boolean dirty = false;
        for (CustomerPhone p : list) {
            if (p.isPrimary()) {
                p.setPrimary(false);
                dirty = true;
            }
        }
        if (dirty) {
            customerPhoneRepository.saveAll(list);
        }
    }

    private boolean anyPrimary(String customerId) {
        return customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .anyMatch(CustomerPhone::isPrimary);
    }

    private static List<CustomerPhoneDraft> normalizedPrimaryDrafts(List<CustomerPhoneDraft> phones) {
        List<CustomerPhoneDraft> normalized = phones.stream()
                .map(d -> new CustomerPhoneDraft(CustomerPhoneNormalizer.normalize(d.phone()), d.primary()))
                .toList();
        int winner = IntStream.range(0, normalized.size())
                .filter(i -> Boolean.TRUE.equals(normalized.get(i).primary()))
                .findFirst()
                .orElse(-1);
        if (winner < 0) {
            return IntStream.range(0, normalized.size())
                    .mapToObj(i -> new CustomerPhoneDraft(normalized.get(i).phone(), i == 0))
                    .toList();
        }
        final int w = winner;
        return IntStream.range(0, normalized.size())
                .mapToObj(i -> new CustomerPhoneDraft(normalized.get(i).phone(), i == w))
                .toList();
    }

    private static void assertDistinctNormalizedInRequest(List<CustomerPhoneDraft> drafts) {
        Set<String> seen = new HashSet<>();
        for (CustomerPhoneDraft d : drafts) {
            if (d.phone().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each phone must contain digits");
            }
            if (!seen.add(d.phone())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate phone in request");
            }
        }
    }

    private void assertPhoneAvailable(String businessId, String normalizedPhone) {
        if (normalizedPhone.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone must contain digits");
        }
        if (customerPhoneRepository.existsByBusinessIdAndPhone(businessId, normalizedPhone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already in use for this business");
        }
    }

    private static String normalizedPhoneOrThrow(String raw) {
        String n = CustomerPhoneNormalizer.normalize(raw);
        if (n.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone must contain digits");
        }
        return n;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Map<String, Object> customerSnapshot(Customer customer) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", customer.getName());
        snapshot.put("email", customer.getEmail());
        snapshot.put("notes", customer.getNotes());
        return snapshot;
    }

    private Map<String, Object> compactDiff(Map<String, Object> oldState, Map<String, Object> newState) {
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String key : oldState.keySet()) {
            Object oldVal = oldState.get(key);
            Object newVal = newState.get(key);
            if (!Objects.equals(oldVal, newVal)) {
                diff.put(key, map("old", oldVal, "new", newVal));
            }
        }
        return diff;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private void publishCustomerEvent(String businessId, Customer customer, String actorUserId,
                                      String eventType, Object diff) {
        AuditEventActorType actorType = actorUserId != null && !actorUserId.isBlank()
                ? AuditEventActorType.USER
                : AuditEventActorType.SYSTEM;
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.CUSTOMERS, eventType, AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(actorUserId, actorType)
                .target("customer", customer.getId())
                .targetLabel(customer.getName())
                .source("web_admin")
                .diff(diff)
                .build());
    }
}
