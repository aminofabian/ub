package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import zelisline.ub.credits.api.dto.AddCustomerPhoneRequest;
import zelisline.ub.credits.api.dto.CreateCustomerRequest;
import zelisline.ub.credits.api.dto.CreditAccountSummaryResponse;
import zelisline.ub.credits.api.dto.CustomerPhoneDraft;
import zelisline.ub.credits.api.dto.CustomerPhoneResponse;
import zelisline.ub.credits.api.dto.CustomerResponse;
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
        return toResponseSingle(businessId, customer);
    }

    @Transactional
    public CustomerResponse patch(String businessId, String customerId, PatchCustomerRequest patch) {
        Customer customer = loadActive(businessId, customerId);
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

        if (patch.creditLimit() != null) {
            CreditAccount account = creditAccountRepository
                    .findByCustomerIdAndBusinessId(customerId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
            if (patch.creditAccountVersion() != null && patch.creditAccountVersion() != account.getVersion()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale credit account version");
            }
            account.setCreditLimit(patch.creditLimit());
            creditAccountRepository.save(account);
        }
        return toResponseSingle(businessId, customer);
    }

    @Transactional
    public void softDelete(String businessId, String customerId) {
        Customer customer = loadActive(businessId, customerId);
        customer.setDeletedAt(Instant.now());
        customerRepository.save(customer);
        customerPhoneRepository.deleteByCustomerId(customerId);
    }

    @Transactional
    public CustomerResponse addPhone(String businessId, String customerId, AddCustomerPhoneRequest request) {
        loadActive(businessId, customerId);
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
        return toResponseSingle(businessId, customerRepository.findById(customerId).orElseThrow());
    }

    @Transactional
    public CustomerResponse setPrimaryPhone(String businessId, String customerId, String phoneId) {
        loadActive(businessId, customerId);
        CustomerPhone row = customerPhoneRepository.findById(phoneId)
                .filter(p -> businessId.equals(p.getBusinessId()) && customerId.equals(p.getCustomerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Phone not found"));
        clearPrimary(customerId);
        row.setPrimary(true);
        customerPhoneRepository.save(row);
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
}
