package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.KioskPayAccountResponse;
import zelisline.ub.payments.api.dto.KioskPayAccountSummary;
import zelisline.ub.payments.api.dto.KioskPayLedgerEntryResponse;
import zelisline.ub.payments.api.dto.UpdateKioskPayAccountRequest;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.KioskPayAccountStatuses;
import zelisline.ub.payments.domain.KioskPayLedgerEntry;
import zelisline.ub.payments.domain.KioskPayLedgerEntryTypes;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.repository.KioskPayAccountRepository;
import zelisline.ub.payments.repository.KioskPayLedgerEntryRepository;
import zelisline.ub.platform.realtime.RealtimeBridge;

/**
 * Tenant Kiosk Pay account + immutable ledger.
 */
@Service
@RequiredArgsConstructor
public class KioskPayWalletService {

    private final KioskPayAccountRepository accountRepository;
    private final KioskPayLedgerEntryRepository ledgerRepository;
    private final PlatformKioskPaySettingsService platformSettings;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public KioskPayAccountResponse getAccount(String businessId) {
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        KioskPayAccount account = accountRepository.findByBusinessId(businessId).orElse(null);
        return toResponse(account, settings, businessId);
    }

    @Transactional
    public KioskPayAccountResponse updateAccount(String businessId, UpdateKioskPayAccountRequest body) {
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        KioskPayAccount account = getOrCreate(businessId);

        if (body.payoutPhone() != null) {
            String phone = body.payoutPhone().trim();
            if (phone.isBlank()) {
                account.setPayoutPhone(null);
            } else {
                String normalized = StkPhoneNormalizer.normalize(phone);
                if (normalized == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "A valid M-Pesa phone number is required (e.g. 2547XXXXXXXX)");
                }
                account.setPayoutPhone(normalized);
            }
        }
        if (body.storefrontEnabled() != null) {
            account.setStorefrontEnabled(body.storefrontEnabled());
        }
        if (Boolean.TRUE.equals(body.activate())) {
            if (!settings.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Kiosk Pay is not enabled by the platform yet");
            }
            if (platformSettings.paystackCredentials().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Platform Paystack credentials are not configured");
            }
            if (account.getPayoutPhone() == null || account.getPayoutPhone().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Set an M-Pesa payout phone before activating Kiosk Pay");
            }
            account.setStatus(KioskPayAccountStatuses.ACTIVE);
        } else if (Boolean.FALSE.equals(body.activate())) {
            account.setStatus(KioskPayAccountStatuses.OFF);
        }

        return toResponse(accountRepository.save(account), settings, businessId);
    }

    @Transactional(readOnly = true)
    public List<KioskPayLedgerEntryResponse> listLedger(String businessId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        return ledgerRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, capped))
                .stream()
                .map(this::toLedgerResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isStorefrontCollectEnabled(String businessId) {
        if (!platformSettings.isProductEnabled()) {
            return false;
        }
        return accountRepository.findByBusinessId(businessId)
                .filter(KioskPayAccount::isActive)
                .filter(KioskPayAccount::isStorefrontEnabled)
                .isPresent();
    }

    /**
     * POS can collect via platform KopoKopo STK when the product is on and the tenant
     * account is active. Platform KopoKopo credentials are checked at push time.
     */
    @Transactional(readOnly = true)
    public boolean isPosCollectEnabled(String businessId) {
        if (!platformSettings.isProductEnabled()) {
            return false;
        }
        return accountRepository.findByBusinessId(businessId)
                .filter(KioskPayAccount::isActive)
                .isPresent();
    }

    @Transactional(readOnly = true)
    public zelisline.ub.payments.api.dto.KioskPayPosAvailabilityResponse posAvailability(String businessId) {
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        boolean platformEnabled = settings.isEnabled();
        boolean accountActive = accountRepository.findByBusinessId(businessId)
                .filter(KioskPayAccount::isActive)
                .isPresent();
        boolean stkConfigured = platformSettings.kopokopoCredentials().isPresent();
        boolean available = platformEnabled && accountActive && stkConfigured;
        String reason;
        if (!platformEnabled) {
            reason = "Kiosk Pay is not enabled on this platform";
        } else if (!accountActive) {
            reason = "Activate Kiosk Pay under Payments → Kiosk Pay";
        } else if (!stkConfigured) {
            reason = "Platform KopoKopo credentials are not configured yet";
        } else {
            reason = null;
        }
        return new zelisline.ub.payments.api.dto.KioskPayPosAvailabilityResponse(
                available,
                platformEnabled,
                accountActive,
                stkConfigured,
                settings.getCurrency(),
                reason);
    }

    /**
     * Credit available balance for a verified Kiosk Pay collection.
     * Credits gross, then deducts any provider processing fee (Paystack/KopoKopo).
     * There is no platform markup.
     */
    @Transactional
    public void creditPaymentCapture(
            String businessId,
            BigDecimal grossAmount,
            String currency,
            String reference,
            String contextType,
            String contextId,
            String gatewayCheckoutId
    ) {
        creditPaymentCapture(
                businessId, grossAmount, currency, reference, contextType, contextId, gatewayCheckoutId, null);
    }

    @Transactional
    public void creditPaymentCapture(
            String businessId,
            BigDecimal grossAmount,
            String currency,
            String reference,
            String contextType,
            String contextId,
            String gatewayCheckoutId,
            BigDecimal providerFee
    ) {
        if (grossAmount == null || grossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (reference != null && ledgerRepository.findByReference(reference).isPresent()) {
            return;
        }

        // Settlement is bookkeeping for funds already collected — it must not be
        // blocked by the platform product switch. An order initiated while Kiosk Pay
        // was enabled still has to credit the merchant wallet even if the product
        // was toggled off in between.
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        KioskPayAccount account = getOrCreate(businessId);

        BigDecimal fee = providerFee != null ? providerFee.max(BigDecimal.ZERO) : BigDecimal.ZERO;
        fee = fee.setScale(2, RoundingMode.HALF_UP);
        if (fee.compareTo(grossAmount) > 0) {
            fee = grossAmount;
        }
        BigDecimal net = grossAmount.subtract(fee);

        String cur = currency != null && !currency.isBlank() ? currency : settings.getCurrency();

        applyDelta(account, net, BigDecimal.ZERO);
        account.setLifetimeIn(account.getLifetimeIn().add(grossAmount));
        accountRepository.save(account);

        writeEntry(
                account,
                KioskPayLedgerEntryTypes.PAYMENT_CAPTURE,
                KioskPayLedgerEntryTypes.CREDIT,
                grossAmount,
                cur,
                net,
                BigDecimal.ZERO,
                reference,
                contextType,
                contextId,
                null,
                gatewayCheckoutId,
                "Gross collection");

        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            writeEntry(
                    account,
                    KioskPayLedgerEntryTypes.PROVIDER_FEE,
                    KioskPayLedgerEntryTypes.DEBIT,
                    fee,
                    cur,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    reference != null ? reference + ":provider-fee" : null,
                    contextType,
                    contextId,
                    null,
                    gatewayCheckoutId,
                    "Provider processing fee");
        }
        publishBalance(account, cur, "PAYMENT_CAPTURE");
    }

    @Transactional
    public void holdForWithdraw(
            KioskPayAccount account,
            BigDecimal amount,
            String currency,
            String withdrawalId,
            String reference
    ) {
        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient Kiosk Pay balance");
        }
        applyDelta(account, amount.negate(), amount);
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.WITHDRAW_HOLD,
                KioskPayLedgerEntryTypes.DEBIT,
                amount,
                currency == null || currency.isBlank() ? "KES" : currency,
                amount.negate(),
                amount,
                reference,
                "WITHDRAWAL",
                withdrawalId,
                withdrawalId,
                null,
                "Withdraw hold");
        publishBalance(account, currency == null || currency.isBlank() ? "KES" : currency, "WITHDRAW_HOLD");
    }

    @Transactional
    public void settleWithdraw(
            KioskPayAccount account,
            BigDecimal amount,
            String currency,
            String withdrawalId,
            String reference
    ) {
        applyDelta(account, BigDecimal.ZERO, amount.negate());
        account.setLifetimeOut(account.getLifetimeOut().add(amount));
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.WITHDRAW_SETTLE,
                KioskPayLedgerEntryTypes.DEBIT,
                amount,
                currency == null || currency.isBlank() ? "KES" : currency,
                BigDecimal.ZERO,
                amount.negate(),
                reference,
                "WITHDRAWAL",
                withdrawalId,
                withdrawalId,
                null,
                "Withdraw settled");
        publishBalance(account, currency == null || currency.isBlank() ? "KES" : currency, "WITHDRAW_SETTLE");
    }

    @Transactional
    public void releaseWithdrawHold(
            KioskPayAccount account,
            BigDecimal amount,
            String currency,
            String withdrawalId,
            String reference
    ) {
        applyDelta(account, amount, amount.negate());
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.WITHDRAW_RELEASE,
                KioskPayLedgerEntryTypes.CREDIT,
                amount,
                currency == null || currency.isBlank() ? "KES" : currency,
                amount,
                amount.negate(),
                reference,
                "WITHDRAWAL",
                withdrawalId,
                withdrawalId,
                null,
                "Withdraw failed — hold released");
        publishBalance(account, currency == null || currency.isBlank() ? "KES" : currency, "WITHDRAW_RELEASE");
    }

    /**
     * Merchant funded their own wallet (M-Pesa STK to the platform till). Unlike a
     * collection this is not sales revenue — it is float the merchant put in so they
     * can sell airtime, so it never touches {@code lifetimeIn}.
     */
    @Transactional
    public void creditTopUp(
            String businessId,
            BigDecimal amount,
            String currency,
            String reference,
            String gatewayCheckoutId
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (reference != null && ledgerRepository.findByReference(reference).isPresent()) {
            return;
        }
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        KioskPayAccount account = getOrCreate(businessId);
        String cur = currency != null && !currency.isBlank() ? currency : settings.getCurrency();

        applyDelta(account, amount, BigDecimal.ZERO);
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.TOPUP,
                KioskPayLedgerEntryTypes.CREDIT,
                amount,
                cur,
                amount,
                BigDecimal.ZERO,
                reference,
                "TOPUP",
                null,
                null,
                gatewayCheckoutId,
                "Wallet top-up");
        publishBalance(account, cur, "TOPUP");
    }

    /**
     * Reserve airtime face value before handing the request to the provider, so a
     * merchant can never sell more airtime than their wallet can cover. Mirrors
     * {@link #holdForWithdraw} — held funds sit in pending until the provider's
     * callback settles or releases them.
     */
    @Transactional
    public void holdForAirtime(
            KioskPayAccount account,
            BigDecimal amount,
            String currency,
            String orderId,
            String reference
    ) {
        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not enough Kiosk Pay balance to send this airtime");
        }
        String cur = currency == null || currency.isBlank() ? "KES" : currency;
        applyDelta(account, amount.negate(), amount);
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.AIRTIME_HOLD,
                KioskPayLedgerEntryTypes.DEBIT,
                amount,
                cur,
                amount.negate(),
                amount,
                reference,
                "AIRTIME",
                orderId,
                null,
                null,
                "Airtime reserved");
        publishBalance(account, cur, "AIRTIME_HOLD");
    }

    /** Airtime delivered — clear the hold and credit the merchant's margin. */
    @Transactional
    public void settleAirtime(
            KioskPayAccount account,
            BigDecimal amount,
            BigDecimal commission,
            String currency,
            String orderId,
            String referenceStem
    ) {
        String cur = currency == null || currency.isBlank() ? "KES" : currency;
        applyDelta(account, BigDecimal.ZERO, amount.negate());
        account.setLifetimeOut(account.getLifetimeOut().add(amount));
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.AIRTIME_SETTLE,
                KioskPayLedgerEntryTypes.DEBIT,
                amount,
                cur,
                BigDecimal.ZERO,
                amount.negate(),
                referenceStem,
                "AIRTIME",
                orderId,
                null,
                null,
                "Airtime delivered");

        BigDecimal margin = commission == null ? BigDecimal.ZERO : commission.max(BigDecimal.ZERO);
        if (margin.compareTo(BigDecimal.ZERO) > 0) {
            applyDelta(account, margin, BigDecimal.ZERO);
            accountRepository.save(account);
            writeEntry(
                    account,
                    KioskPayLedgerEntryTypes.AIRTIME_COMMISSION,
                    KioskPayLedgerEntryTypes.CREDIT,
                    margin,
                    cur,
                    margin,
                    BigDecimal.ZERO,
                    referenceStem != null ? referenceStem + ":commission" : null,
                    "AIRTIME",
                    orderId,
                    null,
                    null,
                    "Airtime commission");
        }
        publishBalance(account, cur, "AIRTIME_SETTLE");
    }

    /** Airtime failed — hand the reserved funds back to available balance. */
    @Transactional
    public void releaseAirtimeHold(
            KioskPayAccount account,
            BigDecimal amount,
            String currency,
            String orderId,
            String reference
    ) {
        String cur = currency == null || currency.isBlank() ? "KES" : currency;
        applyDelta(account, amount, amount.negate());
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.AIRTIME_RELEASE,
                KioskPayLedgerEntryTypes.CREDIT,
                amount,
                cur,
                amount,
                amount.negate(),
                reference,
                "AIRTIME",
                orderId,
                null,
                null,
                "Airtime failed — funds released");
        publishBalance(account, cur, "AIRTIME_RELEASE");
    }

    /**
     * Super-admin manual wallet adjustment (reversal / correction). Positive delta
     * credits available balance; negative debits it (must not go below zero).
     */
    @Transactional
    public KioskPayAccountResponse adjustBalance(String businessId, BigDecimal delta, String note) {
        if (delta == null || delta.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delta must be a non-zero amount");
        }
        if (delta.abs().compareTo(new BigDecimal("100000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "adjustment amount is too large");
        }
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        KioskPayAccount account = getOrCreate(businessId);
        String currency = settings.getCurrency() != null && !settings.getCurrency().isBlank()
                ? settings.getCurrency()
                : "KES";
        applyDelta(account, delta, BigDecimal.ZERO);
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.ADJUSTMENT,
                delta.signum() > 0 ? KioskPayLedgerEntryTypes.CREDIT : KioskPayLedgerEntryTypes.DEBIT,
                delta.abs(),
                currency,
                delta,
                BigDecimal.ZERO,
                "sa-adjust-" + java.util.UUID.randomUUID(),
                "SUPER_ADMIN",
                null,
                null,
                null,
                note != null && note.length() > 512 ? note.substring(0, 512) : note);
        publishBalance(account, currency, "ADJUSTMENT");
        return toResponse(account, settings, businessId);
    }

    /**
     * Withdraw request path: ensure the row exists, then take a pessimistic lock so
     * the daily-limit and one-in-flight checks are atomic per business.
     */
    @Transactional
    public KioskPayAccount getOrCreateForUpdate(String businessId) {
        getOrCreate(businessId);
        return accountRepository.findByBusinessIdForUpdate(businessId)
                .orElseGet(() -> getOrCreate(businessId));
    }

    @Transactional(readOnly = true)
    public List<KioskPayAccountResponse> listAccountsForSuperAdmin(int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        PlatformKioskPaySettings settings = platformSettings.loadSingleton();
        return accountRepository
                .findAll(org.springframework.data.domain.PageRequest.of(0, capped,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")))
                .stream()
                .map(a -> toResponse(a, settings, a.getBusinessId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public KioskPayAccountSummary accountSummaryForSuperAdmin() {
        return accountRepository.summarize();
    }

    @Transactional
    public KioskPayAccount getOrCreate(String businessId) {
        return accountRepository.findByBusinessId(businessId).orElseGet(() -> {
            KioskPayAccount created = new KioskPayAccount();
            created.setBusinessId(businessId);
            created.setStatus(KioskPayAccountStatuses.OFF);
            try {
                return accountRepository.save(created);
            } catch (DataIntegrityViolationException e) {
                return accountRepository.findByBusinessId(businessId)
                        .orElseThrow(() -> e);
            }
        });
    }

    private void publishBalance(KioskPayAccount account, String currency, String reason) {
        if (account == null || account.getBusinessId() == null) {
            return;
        }
        eventPublisher.publishEvent(new RealtimeBridge.KioskPayBalanceUpdatedEvent(
                account.getBusinessId(),
                account.getAvailableBalance(),
                account.getPendingBalance(),
                currency != null && !currency.isBlank() ? currency : "KES",
                account.getStatus(),
                reason));
    }

    private static void applyDelta(KioskPayAccount account, BigDecimal availableDelta, BigDecimal pendingDelta) {
        account.setAvailableBalance(account.getAvailableBalance().add(availableDelta));
        account.setPendingBalance(account.getPendingBalance().add(pendingDelta));
        if (account.getAvailableBalance().compareTo(BigDecimal.ZERO) < 0
                || account.getPendingBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kiosk Pay balance would go negative");
        }
    }

    private void writeEntry(
            KioskPayAccount account,
            String entryType,
            String direction,
            BigDecimal amount,
            String currency,
            BigDecimal availableDelta,
            BigDecimal pendingDelta,
            String reference,
            String contextType,
            String contextId,
            String withdrawalId,
            String gatewayCheckoutId,
            String note
    ) {
        if (reference != null && ledgerRepository.findByReference(reference).isPresent()) {
            return;
        }
        KioskPayLedgerEntry entry = new KioskPayLedgerEntry();
        entry.setBusinessId(account.getBusinessId());
        entry.setAccountId(account.getId());
        entry.setEntryType(entryType);
        entry.setDirection(direction);
        entry.setAmount(amount);
        entry.setCurrency(currency);
        entry.setAvailableDelta(availableDelta);
        entry.setPendingDelta(pendingDelta);
        entry.setBalanceAfterAvailable(account.getAvailableBalance());
        entry.setBalanceAfterPending(account.getPendingBalance());
        entry.setReference(reference);
        entry.setContextType(contextType);
        entry.setContextId(contextId);
        entry.setWithdrawalId(withdrawalId);
        entry.setGatewayCheckoutId(gatewayCheckoutId);
        entry.setNote(note);
        try {
            ledgerRepository.save(entry);
        } catch (DataIntegrityViolationException e) {
            // A duplicate ledger reference means a concurrent duplicate operation
            // (double credit / settle / release). Let it roll the transaction back so
            // the balance change above is never persisted without its ledger entry;
            // callers retry (webhook re-delivery, poller, withdraw reconciler).
            throw e;
        }
    }

    private KioskPayAccountResponse toResponse(
            KioskPayAccount account,
            PlatformKioskPaySettings settings,
            String businessId
    ) {
        // Platform markup removed — tenants only pay provider fees pass-through.
        BigDecimal fee = BigDecimal.ZERO;
        if (account == null) {
            return new KioskPayAccountResponse(
                    null,
                    businessId,
                    KioskPayAccountStatuses.OFF,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    fee,
                    fee,
                    true,
                    settings.isEnabled(),
                    settings.getMinWithdrawAmount(),
                    settings.getDailyWithdrawLimit(),
                    null);
        }
        return new KioskPayAccountResponse(
                account.getId(),
                account.getBusinessId(),
                account.getStatus(),
                account.getPayoutPhone(),
                account.getAvailableBalance(),
                account.getPendingBalance(),
                account.getLifetimeIn(),
                account.getLifetimeOut(),
                fee,
                fee,
                account.isStorefrontEnabled(),
                settings.isEnabled(),
                settings.getMinWithdrawAmount(),
                settings.getDailyWithdrawLimit(),
                account.getUpdatedAt());
    }

    private KioskPayLedgerEntryResponse toLedgerResponse(KioskPayLedgerEntry e) {
        return new KioskPayLedgerEntryResponse(
                e.getId(),
                e.getEntryType(),
                e.getDirection(),
                e.getAmount(),
                e.getCurrency(),
                e.getAvailableDelta(),
                e.getPendingDelta(),
                e.getBalanceAfterAvailable(),
                e.getReference(),
                e.getContextType(),
                e.getContextId(),
                e.getNote(),
                e.getCreatedAt());
    }
}
