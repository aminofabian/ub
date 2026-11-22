package zelisline.ub.wholesale.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.storefront.application.PublicStorefrontContextService;
import zelisline.ub.tenancy.api.dto.StoreTypeCodes;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.wholesale.api.dto.ApplyTradeAccountRequest;
import zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest;
import zelisline.ub.wholesale.api.dto.TradeAccountResponse;
import zelisline.ub.wholesale.domain.TradeAccount;
import zelisline.ub.wholesale.repository.TradeAccountRepository;

/**
 * Buyers' accounts with a supplier's shop (ADR 0038, P1-6, decision D6): ordering is open to
 * anyone who pays up front; a buyer applies for an account and the supplier approves it before
 * selling to them on credit.
 *
 * <p>An account is found by phone number. The number is not verified, so an account is a note the
 * supplier keeps about a buyer, not proof of who is ordering; the supplier still answers each order.
 */
@Service
@RequiredArgsConstructor
public class TradeAccountService {

    private final TradeAccountRepository tradeAccountRepository;
    private final PublicStorefrontContextService storefrontContextService;
    private final TenancyService tenancyService;
    private final zelisline.ub.wholesale.repository.TradeAccountPriceRepository priceRepository;
    private final zelisline.ub.catalog.repository.ItemRepository itemRepository;

    /**
     * A buyer applies. Applying again with the same number changes nothing once the supplier has
     * decided, and the caller is never told what was decided: the reply is the same every time.
     */
    @Transactional
    public void apply(String slug, ApplyTradeAccountRequest req) {
        String businessId = storefrontContextService.requireForSlug(slug).business().getId();
        if (!StoreTypeCodes.isSupplierBusiness(tenancyService.currentStoreTypesForTenant(businessId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This shop does not keep trade accounts");
        }
        String key = phoneKey(req.buyerPhone());
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
        }
        Optional<TradeAccount> existing = tradeAccountRepository.findByBusinessIdAndPhoneKey(businessId, key);
        if (existing.isPresent()) {
            TradeAccount account = existing.get();
            if (TradeAccount.APPLIED.equals(account.getStatus())) {
                fill(account, req);
                tradeAccountRepository.save(account);
            }
            return;
        }
        TradeAccount account = new TradeAccount();
        account.setBusinessId(businessId);
        account.setPhoneKey(key);
        fill(account, req);
        tradeAccountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public List<TradeAccountResponse> list(String businessId, String status) {
        List<TradeAccount> rows = status == null || status.isBlank()
                ? tradeAccountRepository.findTop200ByBusinessIdOrderByCreatedAtDesc(businessId)
                : tradeAccountRepository.findTop200ByBusinessIdAndStatusOrderByCreatedAtDesc(
                        businessId, status.trim().toLowerCase(Locale.ROOT));
        return rows.stream().map(TradeAccountService::toResponse).toList();
    }

    @Transactional
    public TradeAccountResponse decide(String businessId, String accountId, DecideTradeAccountRequest req) {
        TradeAccount account = tradeAccountRepository.findByIdAndBusinessId(accountId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        account.setStatus(req.status());
        if (TradeAccount.APPROVED.equals(req.status())) {
            account.setCreditLimit(req.creditLimit());
            account.setTermsDays(req.termsDays());
            // A discount follows a shop, never a phone number anyone could type.
            if (account.getBuyerBusinessId() == null) {
                account.setDiscountPercent(null);
            } else if (req.discountPercent() != null) {
                // Left out means "leave it as it is"; send 0 to remove it.
                account.setDiscountPercent(req.discountPercent());
            }
        }
        tradeAccountRepository.save(account);
        return toResponse(account);
    }

    /**
     * Opens an account for the shop a verified order came from, or ties the phone's existing
     * account to that shop, and applies the decision. Only an order placed by a signed-in
     * marketplace buyer names a shop.
     */
    @Transactional
    public TradeAccountResponse decideForVerifiedBuyer(
            String businessId,
            String buyerBusinessId,
            String buyerPhone,
            String buyerName,
            String buyerBusinessName,
            DecideTradeAccountRequest req
    ) {
        String key = phoneKey(buyerPhone);
        if (buyerBusinessId == null || key == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This order was not placed by a signed-in marketplace buyer");
        }
        TradeAccount account = tradeAccountRepository.findByBusinessIdAndBuyerBusinessId(businessId, buyerBusinessId)
                .or(() -> tradeAccountRepository.findByBusinessIdAndPhoneKey(businessId, key))
                .orElseGet(() -> {
                    TradeAccount created = new TradeAccount();
                    created.setBusinessId(businessId);
                    created.setPhoneKey(key);
                    created.setBuyerPhone(buyerPhone);
                    created.setBuyerName(buyerName);
                    return created;
                });
        account.setBuyerBusinessId(buyerBusinessId);
        if (buyerBusinessName != null && !buyerBusinessName.isBlank()) {
            account.setBusinessName(buyerBusinessName.trim());
        }
        tradeAccountRepository.save(account);
        return decide(businessId, account.getId(), req);
    }

    /** The prices this buyer's account has for particular products (P4). */
    @Transactional(readOnly = true)
    public List<zelisline.ub.wholesale.api.dto.TradeAccountPriceDto> prices(String businessId, String accountId) {
        requireAccount(businessId, accountId);
        List<zelisline.ub.wholesale.api.dto.TradeAccountPriceDto> out = new java.util.ArrayList<>();
        for (zelisline.ub.wholesale.domain.TradeAccountPrice price
                : priceRepository.findByBusinessIdAndTradeAccountId(businessId, accountId)) {
            String name = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(price.getItemId(), businessId)
                    .map(zelisline.ub.catalog.domain.Item::getName)
                    .orElse(null);
            out.add(new zelisline.ub.wholesale.api.dto.TradeAccountPriceDto(price.getItemId(), price.getUnitPrice(), name));
        }
        return out;
    }

    /**
     * Replaces the account's price list with what is sent. Only an account that belongs to a shop
     * can have one: a price for a typed phone number would be a price for anyone who types it.
     */
    @Transactional
    public List<zelisline.ub.wholesale.api.dto.TradeAccountPriceDto> replacePrices(
            String businessId, String accountId, List<zelisline.ub.wholesale.api.dto.TradeAccountPriceDto> prices) {
        TradeAccount account = requireAccount(businessId, accountId);
        if (account.getBuyerBusinessId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only an account that belongs to a shop can have its own prices");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (zelisline.ub.wholesale.api.dto.TradeAccountPriceDto price : prices) {
            if (!seen.add(price.itemId().trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A product is on the list twice");
            }
            // Only this shop's own product.
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(price.itemId().trim(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "A product on the list was not found"));
        }
        priceRepository.deleteByBusinessIdAndTradeAccountId(businessId, accountId);
        priceRepository.flush();
        for (zelisline.ub.wholesale.api.dto.TradeAccountPriceDto price : prices) {
            zelisline.ub.wholesale.domain.TradeAccountPrice row = new zelisline.ub.wholesale.domain.TradeAccountPrice();
            row.setBusinessId(businessId);
            row.setTradeAccountId(accountId);
            row.setItemId(price.itemId().trim());
            row.setUnitPrice(price.unitPrice().setScale(2, java.math.RoundingMode.HALF_UP));
            priceRepository.save(row);
        }
        return prices(businessId, accountId);
    }

    /** This account's own price per product id. Empty when it has none. */
    @Transactional(readOnly = true)
    public java.util.Map<String, java.math.BigDecimal> priceList(String businessId, String accountId) {
        java.util.Map<String, java.math.BigDecimal> out = new java.util.HashMap<>();
        for (zelisline.ub.wholesale.domain.TradeAccountPrice price
                : priceRepository.findByBusinessIdAndTradeAccountId(businessId, accountId)) {
            out.put(price.getItemId(), price.getUnitPrice());
        }
        return out;
    }

    private TradeAccount requireAccount(String businessId, String accountId) {
        return tradeAccountRepository.findByIdAndBusinessId(accountId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    /** The approved account that belongs to this buying shop, if there is one. */
    @Transactional(readOnly = true)
    public Optional<TradeAccount> approvedForShop(String businessId, String buyerBusinessId) {
        if (buyerBusinessId == null) {
            return Optional.empty();
        }
        return tradeAccountRepository.findByBusinessIdAndBuyerBusinessId(businessId, buyerBusinessId)
                .filter(a -> TradeAccount.APPROVED.equals(a.getStatus()));
    }

    /** The approved account for this phone number at this shop, if there is one. */
    @Transactional(readOnly = true)
    public Optional<TradeAccount> approvedFor(String businessId, String rawPhone) {
        String key = phoneKey(rawPhone);
        if (key == null) {
            return Optional.empty();
        }
        return tradeAccountRepository.findByBusinessIdAndPhoneKey(businessId, key)
                .filter(a -> TradeAccount.APPROVED.equals(a.getStatus()));
    }

    /** One spelling per number (2547XXXXXXXX). Null when it is not a usable number. */
    public static String phoneKey(String rawPhone) {
        return StkPhoneNormalizer.normalize(rawPhone);
    }

    private static void fill(TradeAccount account, ApplyTradeAccountRequest req) {
        account.setBuyerPhone(req.buyerPhone().trim());
        account.setBuyerName(req.buyerName().trim());
        account.setBusinessName(blankToNull(req.businessName()));
        account.setNote(blankToNull(req.note()));
    }

    private static TradeAccountResponse toResponse(TradeAccount a) {
        return new TradeAccountResponse(
                a.getId(), a.getStatus(), a.getBuyerName(), a.getBuyerPhone(), a.getBusinessName(),
                a.getNote(), a.getCreditLimit(), a.getTermsDays(), a.getCreatedAt(),
                a.getBuyerBusinessId() != null, a.getDiscountPercent());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
