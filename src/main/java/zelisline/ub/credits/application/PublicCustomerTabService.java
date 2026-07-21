package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.PublicCustomerTabResponse;
import zelisline.ub.credits.api.dto.PublicTabStkResponse;
import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PublicCustomerTabService {

    private final CustomerRepository customerRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CustomerTabPurchasesService customerTabPurchasesService;
    private final MpesaStkIntentService mpesaStkIntentService;
    private final MpesaStkIntentRepository mpesaStkIntentRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public PublicCustomerTabResponse overview(String businessId, String phoneRaw) {
        ResolvedCustomer resolved = resolveCustomerOrThrow(businessId, phoneRaw);
        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null ? business.getName().trim() : "Shop";
        String currency = business != null && business.getCurrency() != null ? business.getCurrency().trim() : "KES";
        BigDecimal owed = resolved.account().getBalanceOwed() != null
                ? resolved.account().getBalanceOwed()
                : BigDecimal.ZERO;
        List<TabPurchaseRowResponse> purchases =
                customerTabPurchasesService.list(businessId, resolved.customer().getId());
        String display = KenyanPhoneForms.toLocal07(phoneRaw);
        if (display == null) {
            display = phoneRaw != null ? phoneRaw.trim() : "";
        }
        return new PublicCustomerTabResponse(
                resolved.customer().getName(),
                display,
                shopName,
                currency,
                owed,
                purchases);
    }

    @Transactional
    public PublicTabStkResponse initiateStk(
            String businessId,
            String phoneRaw,
            BigDecimal amount,
            String stkPhoneOverride,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key required");
        }
        ResolvedCustomer resolved = resolveCustomerOrThrow(businessId, phoneRaw);
        MpesaStkIntent intent = mpesaStkIntentService.initiateArPayment(
                businessId,
                resolved.account().getId(),
                amount,
                idempotencyKey.trim(),
                resolved.customer().getId(),
                stkPhoneOverride);
        BigDecimal owed = resolved.account().getBalanceOwed() != null
                ? resolved.account().getBalanceOwed()
                : BigDecimal.ZERO;
        return new PublicTabStkResponse(
                intent.getId(),
                intent.getCheckoutRequestId(),
                intent.getStatus(),
                intent.getAmount(),
                owed);
    }

    @Transactional(readOnly = true)
    public PublicTabStkResponse stkStatus(String businessId, String phoneRaw, String intentId) {
        ResolvedCustomer resolved = resolveCustomerOrThrow(businessId, phoneRaw);
        MpesaStkIntent intent = mpesaStkIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        if (!businessId.equals(intent.getBusinessId())
                || !resolved.account().getId().equals(intent.getCreditAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        BigDecimal owed = resolved.account().getBalanceOwed() != null
                ? resolved.account().getBalanceOwed()
                : BigDecimal.ZERO;
        // Refresh account in case fulfilled
        owed = creditAccountRepository.findByCustomerIdAndBusinessId(resolved.customer().getId(), businessId)
                .map(CreditAccount::getBalanceOwed)
                .orElse(owed);
        return new PublicTabStkResponse(
                intent.getId(),
                intent.getCheckoutRequestId(),
                intent.getStatus(),
                intent.getAmount(),
                owed);
    }

    private ResolvedCustomer resolveCustomerOrThrow(String businessId, String phoneRaw) {
        if (!KenyanPhoneForms.looksLikeKenyanMobile(phoneRaw)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tab not found");
        }
        Customer customer = null;
        for (String candidate : KenyanPhoneForms.lookupCandidates(phoneRaw)) {
            var page = customerRepository.findByBusinessIdAndPhoneNormalized(
                    businessId, candidate, PageRequest.of(0, 1));
            if (!page.isEmpty()) {
                customer = page.getContent().getFirst();
                break;
            }
        }
        if (customer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tab not found");
        }
        CreditAccount account = creditAccountRepository
                .findByCustomerIdAndBusinessId(customer.getId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tab not found"));
        return new ResolvedCustomer(customer, account);
    }

    private record ResolvedCustomer(Customer customer, CreditAccount account) {
    }
}
