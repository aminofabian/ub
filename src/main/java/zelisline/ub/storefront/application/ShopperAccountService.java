package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.AddCustomerPhoneRequest;
import zelisline.ub.credits.api.dto.CreateCustomerRequest;
import zelisline.ub.credits.api.dto.CustomerPhoneDraft;
import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.application.CreditCustomerStatementService;
import zelisline.ub.credits.application.CreditCustomerStatementService.CreditStatement;
import zelisline.ub.credits.application.CreditCustomerStatementService.StatementLineDto;
import zelisline.ub.credits.application.CustomerDirectoryService;
import zelisline.ub.credits.application.CustomerPhoneVerificationService;
import zelisline.ub.credits.application.CustomerTabPurchasesService;
import zelisline.ub.credits.application.MpesaPayerIdentityService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.storefront.api.dto.ShopperAccountOverviewResponse;
import zelisline.ub.storefront.api.dto.ShopperBalancesResponse;
import zelisline.ub.storefront.api.dto.ShopperLedgerLineResponse;

@Service
@RequiredArgsConstructor
public class ShopperAccountService {

    private static final int MAX_LEDGER = 28;
    private static final int MAX_PAGE = 80;

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditCustomerStatementService creditCustomerStatementService;
    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final WebOrderAdminService webOrderAdminService;
    private final CustomerTabPurchasesService customerTabPurchasesService;
    private final CustomerPhoneVerificationService customerPhoneVerificationService;
    private final CustomerDirectoryService customerDirectoryService;
    private final MpesaPayerIdentityService mpesaPayerIdentityService;

    @Transactional(readOnly = true)
    public String normalizedEmailForUser(String businessId, String userId) {
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return normalizeEmail(user.getEmail());
    }

    @Transactional(readOnly = true)
    public ShopperAccountOverviewResponse overview(String businessId, String userId, int page, int pageSize) {
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return overviewFor(businessId, user, page, pageSize);
    }

    /**
     * Attach a verified Kenyan mobile to this shopper: store it on the user,
     * and tether (or create) the store directory profile so till slips, wallet,
     * and tab show up on the hub.
     */
    @Transactional
    public ShopperAccountOverviewResponse linkPhone(
            String businessId,
            String userId,
            String rawPhone,
            String verificationToken
    ) {
        String phone = customerPhoneVerificationService.consumeRegistrationToken(
                businessId, verificationToken, rawPhone);
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String storePhone = KenyanPhoneForms.toLocal07(phone);
        if (storePhone == null) {
            storePhone = phone;
        }
        user.setPhone(storePhone);
        userRepository.save(user);

        String emailNorm = normalizeEmail(user.getEmail());
        Customer byEmail = findCustomerByEmail(businessId, emailNorm);
        Customer byPhone = findCustomerByPhone(businessId, phone);

        if (byEmail != null && byPhone != null && !byEmail.getId().equals(byPhone.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This number is already on another customer at this shop");
        }

        Customer customer = byEmail != null ? byEmail : byPhone;
        if (customer == null) {
            customerDirectoryService.create(
                    businessId,
                    new CreateCustomerRequest(
                            displayName(user),
                            user.getEmail(),
                            null,
                            null,
                            List.of(new CustomerPhoneDraft(storePhone, true)),
                            null),
                    userId);
            customer = findCustomerByEmail(businessId, emailNorm);
            if (customer == null) {
                customer = findCustomerByPhone(businessId, phone);
            }
            if (customer != null && (customer.getEmail() == null || customer.getEmail().isBlank())) {
                customer.setEmail(user.getEmail());
                customerRepository.save(customer);
            }
        } else {
            String existingEmail = customer.getEmail();
            if (existingEmail != null && !existingEmail.isBlank()) {
                if (!emailNorm.equals(existingEmail.trim().toLowerCase(Locale.ROOT))) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "This number is already on another customer at this shop");
                }
            } else {
                customer.setEmail(user.getEmail());
                customerRepository.save(customer);
            }
            if (!phoneOnCustomer(customer.getId(), phone)) {
                try {
                    customerDirectoryService.addPhone(
                            businessId,
                            customer.getId(),
                            new AddCustomerPhoneRequest(storePhone, true),
                            userId);
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode() != HttpStatus.CONFLICT) {
                        throw ex;
                    }
                }
            }
        }

        if (customer != null) {
            mpesaPayerIdentityService.markSelfVerified(customer, phone);
        }

        return overviewFor(businessId, user, 0, 12);
    }

    private ShopperAccountOverviewResponse overviewFor(String businessId, User user, int page, int pageSize) {
        String emailNorm = normalizeEmail(user.getEmail());

        int p = Math.max(0, page);
        int s = clamp(pageSize);
        Pageable pageable = PageRequest.of(p, s);
        var orderSlice = webOrderAdminService.pageOrdersForShopperEmail(businessId, emailNorm, pageable);

        Customer customer = findCustomerByEmail(businessId, emailNorm);

        BigDecimal kesPerPoint = businessCreditSettingsService
                .resolveForBusiness(businessId)
                .getLoyaltyKesPerPoint()
                .setScale(8, RoundingMode.HALF_UP);

        if (customer == null) {
            return new ShopperAccountOverviewResponse(
                    emailNorm,
                    false,
                    "",
                    zeroBalances(),
                    orderSlice.getContent(),
                    orderSlice.getTotalElements(),
                    p,
                    s,
                    orderSlice.getTotalPages(),
                    List.of(),
                    0,
                    false,
                    kesPerPoint,
                    null,
                    List.of()
            );
        }

        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customer.getId(), businessId).orElse(null);

        CreditStatement stmt = null;
        try {
            stmt = creditCustomerStatementService.assemble(businessId, customer.getId());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
        }

        ShopperBalancesResponse balances;
        if (acc != null) {
            BigDecimal limit = acc.getCreditLimit();
            BigDecimal owedScaled = acc.getBalanceOwed().setScale(2, RoundingMode.HALF_UP);
            BigDecimal available = null;
            if (limit != null) {
                available = limit.subtract(owedScaled).setScale(2, RoundingMode.HALF_UP);
            }
            balances = new ShopperBalancesResponse(
                    acc.getWalletBalance().setScale(2, RoundingMode.HALF_UP),
                    owedScaled,
                    limit == null ? null : limit.setScale(2, RoundingMode.HALF_UP),
                    available,
                    acc.getLoyaltyPoints()
            );
        } else if (stmt != null) {
            balances = new ShopperBalancesResponse(
                    stmt.walletBalance(),
                    stmt.balanceOwed(),
                    null,
                    null,
                    stmt.loyaltyPoints()
            );
        } else {
            balances = zeroBalances();
        }

        List<StatementLineDto> merged = stmt == null ? List.of() : new ArrayList<>(stmt.lines());
        merged.sort(Comparator.comparing(StatementLineDto::at).reversed());
        int totalLedger = merged.size();
        List<StatementLineDto> head = merged.stream().limit(MAX_LEDGER).toList();
        List<ShopperLedgerLineResponse> rows = head.stream()
                .map(l -> new ShopperLedgerLineResponse(
                        l.at(),
                        l.kind(),
                        l.memo(),
                        l.debit(),
                        l.credit()))
                .toList();

        return new ShopperAccountOverviewResponse(
                emailNorm,
                true,
                customer.getName(),
                balances,
                orderSlice.getContent(),
                orderSlice.getTotalElements(),
                p,
                s,
                orderSlice.getTotalPages(),
                rows,
                totalLedger,
                totalLedger > rows.size(),
                kesPerPoint,
                resolveTabPhone(customer.getId()),
                loadTillPurchases(businessId, customer)
        );
    }

    private List<TabPurchaseRowResponse> loadTillPurchases(String businessId, Customer customer) {
        try {
            return customerTabPurchasesService.list(businessId, customer.getId());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return List.of();
            }
            throw ex;
        }
    }

    private Customer findCustomerByEmail(String businessId, String emailNorm) {
        var candidates = customerRepository.findActiveByBusinessIdAndNormalizedEmail(
                businessId,
                emailNorm,
                PageRequest.of(0, 1));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private Customer findCustomerByPhone(String businessId, String rawPhone) {
        for (String candidate : KenyanPhoneForms.lookupCandidates(rawPhone)) {
            var page = customerRepository.findByBusinessIdAndPhoneNormalized(
                    businessId, candidate, PageRequest.of(0, 1));
            if (!page.isEmpty()) {
                return page.getContent().getFirst();
            }
        }
        return null;
    }

    private boolean phoneOnCustomer(String customerId, String rawPhone) {
        var candidates = KenyanPhoneForms.lookupCandidates(rawPhone);
        for (CustomerPhone row : customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId)) {
            if (row.getPhone() != null && candidates.contains(row.getPhone())) {
                return true;
            }
            if (row.getAssignedMsisdn() != null && candidates.contains(row.getAssignedMsisdn())) {
                return true;
            }
        }
        return false;
    }

    private static String displayName(User user) {
        String name = user.getName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return user.getEmail();
    }

    private String resolveTabPhone(String customerId) {
        var phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        CustomerPhone chosen = null;
        for (var row : phones) {
            if (row.isPrimary()) {
                chosen = row;
                break;
            }
            if (chosen == null) {
                chosen = row;
            }
        }
        if (chosen == null) {
            return null;
        }
        String assigned = chosen.getAssignedMsisdn();
        if (assigned != null && !assigned.isBlank()) {
            return assigned.trim();
        }
        String phone = chosen.getPhone();
        return phone == null || phone.isBlank() ? null : phone.trim();
    }

    private static ShopperBalancesResponse zeroBalances() {
        BigDecimal z = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new ShopperBalancesResponse(z, z, null, null, 0);
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no email on file");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int pageSize) {
        if (pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, MAX_PAGE);
    }
}
