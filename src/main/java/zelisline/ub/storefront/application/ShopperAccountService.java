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
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.application.CreditCustomerStatementService;
import zelisline.ub.credits.application.CreditCustomerStatementService.CreditStatement;
import zelisline.ub.credits.application.CreditCustomerStatementService.StatementLineDto;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CreditAccountRepository;
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
    private final CreditAccountRepository creditAccountRepository;
    private final CreditCustomerStatementService creditCustomerStatementService;
    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final WebOrderAdminService webOrderAdminService;

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

        String emailNorm = normalizeEmail(user.getEmail());

        int p = Math.max(0, page);
        int s = clamp(pageSize);
        Pageable pageable = PageRequest.of(p, s);
        var orderSlice = webOrderAdminService.pageOrdersForShopperEmail(businessId, emailNorm, pageable);

        var candidates = customerRepository.findActiveByBusinessIdAndNormalizedEmail(
                businessId,
                emailNorm,
                PageRequest.of(0, 1));

        Customer customer = candidates.isEmpty() ? null : candidates.getFirst();
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
                    kesPerPoint
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
                kesPerPoint
        );
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
