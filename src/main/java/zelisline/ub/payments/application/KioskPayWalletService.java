package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.KioskPayAccountResponse;
import zelisline.ub.payments.api.dto.KioskPayLedgerEntryResponse;
import zelisline.ub.payments.api.dto.UpdateKioskPayAccountRequest;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.KioskPayAccountStatuses;
import zelisline.ub.payments.domain.KioskPayLedgerEntry;
import zelisline.ub.payments.domain.KioskPayLedgerEntryTypes;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.repository.KioskPayAccountRepository;
import zelisline.ub.payments.repository.KioskPayLedgerEntryRepository;

/**
 * Tenant Kiosk Pay account + immutable ledger.
 */
@Service
@RequiredArgsConstructor
public class KioskPayWalletService {

    private final KioskPayAccountRepository accountRepository;
    private final KioskPayLedgerEntryRepository ledgerRepository;
    private final PlatformKioskPaySettingsService platformSettings;

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
            account.setPayoutPhone(phone.isBlank() ? null : phone);
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
        boolean available = platformEnabled && accountActive;
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
     * Credit available balance for a verified Kiosk Pay collection (gross − fee).
     * Idempotent on {@code reference}.
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
        if (grossAmount == null || grossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (reference != null && ledgerRepository.findByReference(reference).isPresent()) {
            return;
        }

        PlatformKioskPaySettings settings = platformSettings.requireEnabledSettings();
        KioskPayAccount account = getOrCreate(businessId);
        if (!account.isActive()) {
            account.setStatus(KioskPayAccountStatuses.ACTIVE);
        }

        BigDecimal feePercent = account.getFeePercentOverride() != null
                ? account.getFeePercentOverride()
                : settings.getFeePercent();
        BigDecimal fee = grossAmount.multiply(feePercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
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
                    KioskPayLedgerEntryTypes.PLATFORM_FEE,
                    KioskPayLedgerEntryTypes.DEBIT,
                    fee,
                    cur,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    reference != null ? reference + ":fee" : null,
                    contextType,
                    contextId,
                    null,
                    gatewayCheckoutId,
                    "Platform fee " + feePercent + "%");
        }
    }

    @Transactional
    public void holdForWithdraw(KioskPayAccount account, BigDecimal amount, String withdrawalId, String reference) {
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
                "KES",
                amount.negate(),
                amount,
                reference,
                "WITHDRAWAL",
                withdrawalId,
                withdrawalId,
                null,
                "Withdraw hold");
    }

    @Transactional
    public void settleWithdraw(KioskPayAccount account, BigDecimal amount, String withdrawalId, String reference) {
        applyDelta(account, BigDecimal.ZERO, amount.negate());
        account.setLifetimeOut(account.getLifetimeOut().add(amount));
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.WITHDRAW_SETTLE,
                KioskPayLedgerEntryTypes.DEBIT,
                amount,
                "KES",
                BigDecimal.ZERO,
                amount.negate(),
                reference,
                "WITHDRAWAL",
                withdrawalId,
                withdrawalId,
                null,
                "Withdraw settled");
    }

    @Transactional
    public void releaseWithdrawHold(KioskPayAccount account, BigDecimal amount, String withdrawalId, String reference) {
        applyDelta(account, amount, amount.negate());
        accountRepository.save(account);
        writeEntry(
                account,
                KioskPayLedgerEntryTypes.WITHDRAW_RELEASE,
                KioskPayLedgerEntryTypes.CREDIT,
                amount,
                "KES",
                amount,
                amount.negate(),
                reference,
                "WITHDRAWAL",
                withdrawalId,
                withdrawalId,
                null,
                "Withdraw failed — hold released");
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
            // concurrent idempotent write
        }
    }

    private KioskPayAccountResponse toResponse(
            KioskPayAccount account,
            PlatformKioskPaySettings settings,
            String businessId
    ) {
        BigDecimal fee = settings.getFeePercent();
        if (account != null && account.getFeePercentOverride() != null) {
            fee = account.getFeePercentOverride();
        }
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
                    settings.getFeePercent(),
                    true,
                    settings.isEnabled(),
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
                settings.getFeePercent(),
                account.isStorefrontEnabled(),
                settings.isEnabled(),
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
