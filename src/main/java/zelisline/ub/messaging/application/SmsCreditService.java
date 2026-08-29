package zelisline.ub.messaging.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.data.domain.PageRequest;
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
import zelisline.ub.messaging.api.dto.SmsCreditBalanceResponse;
import zelisline.ub.messaging.api.dto.SmsCreditUsageResponse;
import zelisline.ub.messaging.domain.BusinessSmsCreditAccount;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsCreditLedgerEntry;
import zelisline.ub.messaging.domain.SmsCreditLedgerKind;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.repository.BusinessSmsCreditAccountRepository;
import zelisline.ub.messaging.repository.SmsCreditLedgerRepository;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Core SMS credit accounting: per-tenant balance, atomic debit (single choke
 * point), credits for purchases/grants, and the monthly included-cycle reset
 * (SMS_CREDITS_SCOPE.md §3, §7, §9).
 */
@Service
@RequiredArgsConstructor
public class SmsCreditService {

    private final BusinessSmsCreditAccountRepository accountRepository;
    private final SmsCreditLedgerRepository ledgerRepository;
    private final BusinessRepository businessRepository;
    private final SmsCreditSettingsService settingsService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ── Reads ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SmsCreditBalanceResponse getBalanceView(String businessId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        BusinessSmsCreditAccount account = accountRepository.findByBusinessId(businessId).orElse(null);
        int allowance = account != null ? allowanceFor(account, businessId) : tierAllowance(businessId);
        int includedUsed = account != null ? account.getIncludedUsed() : 0;
        int purchased = account != null ? account.getPurchasedBalance() : 0;
        int includedRemaining = Math.max(0, allowance - includedUsed);
        int available = includedRemaining + purchased;
        return new SmsCreditBalanceResponse(
                available,
                includedRemaining,
                allowance,
                purchased,
                cycleEndsAt(settings, account),
                settings.getUnitPriceKes(),
                available <= settings.getLowBalanceThreshold(),
                settings.isEnabled(),
                settings.getMinPurchaseCredits(),
                settings.getMaxPurchaseCredits());
    }

    /**
     * Pre-flight check before a provider call. Throws {@link SmsCreditsDepletedException}
     * when the tenant has nothing left. Creates the account row lazily on first use.
     * Deliberately lock-free — the atomic debit under lock is what keeps the ledger
     * non-negative, so the hot send path only pays for one cheap read.
     */
    @Transactional
    public void requireAvailable(String businessId, SmsSendReason reason, String referenceId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        if (!settings.isEnabled()) {
            return;
        }
        BusinessSmsCreditAccount account = ensureExists(businessId, settings);
        int allowance = allowanceFor(account, businessId);
        if (account.available(allowance) <= 0) {
            throw depleted(account, allowance, settings);
        }
    }

    @Transactional(readOnly = true)
    public List<SmsCreditLedgerEntry> ledger(String businessId, int limit) {
        int capped = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return ledgerRepository.findByBusinessIdOrderByCreatedAtDesc(
                businessId, PageRequest.of(0, capped));
    }

    /** Platform-wide usage for the Super Admin dashboard (scope §11). */
    @Transactional(readOnly = true)
    public SmsCreditUsageResponse usage() {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        Instant cycleStart = startOfCycle(settings);

        int includedSent = 0;
        int purchasedSent = 0;
        for (Object[] row : ledgerRepository.sumSpendByKind(cycleStart)) {
            SmsCreditLedgerKind kind = (SmsCreditLedgerKind) row[0];
            int amount = Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.abs(((Number) row[1]).longValue())));
            if (kind == SmsCreditLedgerKind.INCLUDED_SPEND) {
                includedSent = amount;
            } else if (kind == SmsCreditLedgerKind.PURCHASED_SPEND) {
                purchasedSent = amount;
            }
        }
        int totalSent = Math.addExact(includedSent, purchasedSent);

        List<SmsCreditUsageResponse.TopTenantRow> topRows = topTenantRows(cycleStart);
        int depletedCount = depletedCount(settings);

        return new SmsCreditUsageResponse(
                cycleStart, totalSent, includedSent, purchasedSent, depletedCount, topRows);
    }

    private List<SmsCreditUsageResponse.TopTenantRow> topTenantRows(Instant cycleStart) {
        List<Object[]> top = ledgerRepository.topSpenders(cycleStart, PageRequest.of(0, 10));
        if (top.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> businessIds = top.stream()
                .map(row -> (String) row[0])
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, Business> businesses = businessRepository.findAllById(businessIds).stream()
                .collect(java.util.stream.Collectors.toMap(Business::getId, b -> b));
        java.util.Map<String, BusinessSmsCreditAccount> accounts = accountRepository
                .findByBusinessIdIn(businessIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        BusinessSmsCreditAccount::getBusinessId, a -> a));
        return top.stream().map(row -> {
            String businessId = (String) row[0];
            int sent = Math.toIntExact(Math.min(Integer.MAX_VALUE, ((Number) row[1]).longValue()));
            Business business = businesses.get(businessId);
            BusinessSmsCreditAccount account = accounts.get(businessId);
            Integer tierAllowance = business != null && business.getSubscriptionTier() != null
                    ? settingsService.resolveAllowance(business.getSubscriptionTier())
                    : null;
            int allowance = account != null ? allowanceFor(account, businessId) : (tierAllowance != null ? tierAllowance : 0);
            int available = account != null ? account.available(allowance) : allowance;
            return new SmsCreditUsageResponse.TopTenantRow(
                    businessId,
                    business != null && business.getName() != null ? business.getName() : businessId,
                    business != null && business.getSubscriptionTier() != null
                            ? business.getSubscriptionTier() : "",
                    sent,
                    available);
        }).toList();
    }

    /** Businesses that would be hard-blocked right now (nothing left to spend). */
    private int depletedCount(PlatformSmsCreditSettings settings) {
        List<BusinessSmsCreditAccount> lowAccounts =
                accountRepository.findByPurchasedBalanceLessThanEqual(0);
        if (lowAccounts.isEmpty()) {
            return 0;
        }
        java.util.Set<String> businessIds = lowAccounts.stream()
                .map(BusinessSmsCreditAccount::getBusinessId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, String> tiers = businessRepository.findAllById(businessIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Business::getId,
                        b -> b.getSubscriptionTier() != null ? b.getSubscriptionTier() : ""));
        long depleted = lowAccounts.stream().filter(account -> {
            Integer override = account.getIncludedOverride();
            Integer tierAllowance = override != null ? null
                    : (tiers.get(account.getBusinessId()) != null
                            ? settingsService.resolveAllowance(tiers.get(account.getBusinessId()))
                            : null);
            int allowance = override != null ? override : (tierAllowance != null ? tierAllowance : 0);
            return account.available(allowance) <= 0;
        }).count();
        return Math.toIntExact(depleted);
    }

    // ── Movements (single choke point, row-locked) ─────────────────────

    /**
     * Atomic debit of one credit after a successful provider send. Included
     * allowance is consumed first, then purchased balance (scope §7). Throws
     * {@link SmsCreditsDepletedException} when a concurrent send consumed the
     * last credit between the pre-flight check and this debit.
     *
     * @return new total available balance
     */
    @Transactional
    public int debit(String businessId, SmsSendReason reason, String referenceId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        BusinessSmsCreditAccount account = lockOrCreate(businessId, settings);
        int allowance = allowanceFor(account, businessId);
        if (account.available(allowance) <= 0) {
            throw depleted(account, allowance, settings);
        }
        SmsCreditLedgerKind kind;
        if (account.getIncludedUsed() < allowance) {
            account.setIncludedUsed(account.getIncludedUsed() + 1);
            kind = SmsCreditLedgerKind.INCLUDED_SPEND;
        } else {
            account.setPurchasedBalance(account.getPurchasedBalance() - 1);
            kind = SmsCreditLedgerKind.PURCHASED_SPEND;
        }
        int after = account.available(allowance);
        accountRepository.save(account);
        ledgerRepository.save(newEntry(businessId, -1, after, kind,
                reason != null ? reason.code() : null, referenceId, null));
        return after;
    }

    /** Add purchased credits (STK purchase, SA grant, refund). Kind guards intent. */
    @Transactional
    public int credit(
            String businessId,
            int credits,
            SmsCreditLedgerKind kind,
            String referenceId,
            String actorUserId
    ) {
        if (credits <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credits must be positive");
        }
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        BusinessSmsCreditAccount account = lockOrCreate(businessId, settings);
        account.setPurchasedBalance(account.getPurchasedBalance() + credits);
        int allowance = allowanceFor(account, businessId);
        int after = account.available(allowance);
        accountRepository.save(account);
        ledgerRepository.save(newEntry(businessId, credits, after, kind,
                null, referenceId, actorUserId));
        String eventType = switch (kind) {
            case PURCHASE -> AuditEventTypes.SMS_CREDITS_PURCHASED;
            case GRANT -> AuditEventTypes.SMS_CREDITS_GRANTED;
            default -> null;
        };
        if (eventType != null) {
            publishAudit(eventType, businessId, credits, referenceId, actorUserId);
        }
        publishBalanceEvent(businessId, account, allowance, kind);
        return after;
    }

    /** Super Admin manual credit grant. */
    @Transactional
    public int grant(String businessId, int credits, String note, String actorUserId) {
        return credit(businessId, credits, SmsCreditLedgerKind.GRANT, note, actorUserId);
    }

    /** Monthly cycle reset: zero included_used, bump cycle_started_at, keep purchased. */
    @Transactional
    public void resetCycle(String businessId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        BusinessSmsCreditAccount account = lockOrCreate(businessId, settings);
        int allowance = allowanceFor(account, businessId);
        if (account.getIncludedUsed() == 0 && sameCycle(account, settings)) {
            return; // idempotent — nothing to reset
        }
        account.setIncludedUsed(0);
        account.setCycleStartedAt(startOfCycle(settings));
        account.setLastDigestPct(null);
        int after = account.available(allowance);
        accountRepository.save(account);
        ledgerRepository.save(newEntry(businessId, 0, after,
                SmsCreditLedgerKind.CYCLE_RESET, "cycle_reset", null, null));
        publishAudit(AuditEventTypes.SMS_CREDITS_CYCLE_RESET, businessId, 0, null, null);
    }

    /** SA per-business drill-down: account state (never null; virtual when fresh). */
    @Transactional(readOnly = true)
    public BusinessSmsCreditAccount accountOrVirtual(String businessId) {
        return accountRepository.findByBusinessId(businessId)
                .orElseGet(() -> {
                    BusinessSmsCreditAccount virtual = new BusinessSmsCreditAccount();
                    virtual.setBusinessId(businessId);
                    virtual.setIncludedUsed(0);
                    virtual.setPurchasedBalance(0);
                    virtual.setCycleStartedAt(startOfCycle(settingsService.loadSingleton()));
                    return virtual;
                });
    }

    /** Set the SA per-tenant included allowance override (null clears it). */
    @Transactional
    public BusinessSmsCreditAccount updateIncludedOverride(String businessId, Integer includedOverride) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        BusinessSmsCreditAccount account = lockOrCreate(businessId, settings);
        if (includedOverride != null && includedOverride < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "included override cannot be negative");
        }
        account.setIncludedOverride(includedOverride);
        accountRepository.save(account);
        return account;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private BusinessSmsCreditAccount lockOrCreate(
            String businessId,
            PlatformSmsCreditSettings settings
    ) {
        return accountRepository.findForUpdate(businessId)
                .orElseGet(() -> {
                    BusinessSmsCreditAccount account = new BusinessSmsCreditAccount();
                    account.setBusinessId(businessId);
                    account.setIncludedUsed(0);
                    account.setPurchasedBalance(0);
                    account.setCycleStartedAt(startOfCycle(settings));
                    return accountRepository.save(account);
                });
    }

    /** Lock-free variant for the pre-flight check; tolerates a concurrent create. */
    private BusinessSmsCreditAccount ensureExists(
            String businessId,
            PlatformSmsCreditSettings settings
    ) {
        java.util.Optional<BusinessSmsCreditAccount> existing =
                accountRepository.findByBusinessId(businessId);
        if (existing.isPresent()) {
            return existing.get();
        }
        BusinessSmsCreditAccount account = new BusinessSmsCreditAccount();
        account.setBusinessId(businessId);
        account.setIncludedUsed(0);
        account.setPurchasedBalance(0);
        account.setCycleStartedAt(startOfCycle(settings));
        try {
            return accountRepository.save(account);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return accountRepository.findByBusinessId(businessId).orElseThrow(() -> e);
        }
    }

    private int allowanceFor(BusinessSmsCreditAccount account, String businessId) {
        if (account.getIncludedOverride() != null) {
            return account.getIncludedOverride();
        }
        Integer tierAllowance = tierAllowance(businessId);
        return tierAllowance != null ? tierAllowance : 0;
    }

    private Integer tierAllowance(String businessId) {
        return businessRepository.findById(businessId)
                .map(b -> settingsService.resolveAllowance(b.getSubscriptionTier()))
                .orElse(null);
    }

    private SmsCreditsDepletedException depleted(
            BusinessSmsCreditAccount account,
            int allowance,
            PlatformSmsCreditSettings settings
    ) {
        int includedRemaining = account.includedRemaining(allowance);
        return new SmsCreditsDepletedException(
                "SMS credits depleted — you've used all your included SMS this month. "
                        + "Buy more credits to continue.",
                account.available(allowance),
                includedRemaining,
                account.getPurchasedBalance(),
                settings.getUnitPriceKes());
    }

    private SmsCreditLedgerEntry newEntry(
            String businessId,
            int delta,
            int balanceAfter,
            SmsCreditLedgerKind kind,
            String reason,
            String referenceId,
            String actorUserId
    ) {
        SmsCreditLedgerEntry entry = new SmsCreditLedgerEntry();
        entry.setBusinessId(businessId);
        entry.setDelta(delta);
        entry.setBalanceAfter(balanceAfter);
        entry.setKind(kind);
        entry.setReason(reason);
        entry.setReferenceId(referenceId);
        entry.setCreatedByUserId(actorUserId);
        return entry;
    }

    private Instant startOfCycle(PlatformSmsCreditSettings settings) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(settings.getCycleTimezone()));
        return now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
    }

    private Instant cycleEndsAt(PlatformSmsCreditSettings settings, BusinessSmsCreditAccount account) {
        ZoneId zone = ZoneId.of(settings.getCycleTimezone());
        Instant start = account != null ? account.getCycleStartedAt() : startOfCycle(settings);
        return start.atZone(zone).plusMonths(1).toInstant();
    }

    private boolean sameCycle(BusinessSmsCreditAccount account, PlatformSmsCreditSettings settings) {
        Instant currentStart = startOfCycle(settings);
        Instant accountStart = account.getCycleStartedAt();
        return accountStart != null
                && accountStart.truncatedTo(ChronoUnit.DAYS).equals(currentStart.truncatedTo(ChronoUnit.DAYS));
    }

    /** Tell every open staff session to refresh its SMS credits header chip. */
    private void publishBalanceEvent(
            String businessId,
            BusinessSmsCreditAccount account,
            int allowance,
            SmsCreditLedgerKind kind
    ) {
        try {
            eventPublisher.publishEvent(new RealtimeBridge.SmsCreditsUpdatedEvent(
                    businessId,
                    account.available(allowance),
                    account.includedRemaining(allowance),
                    account.getPurchasedBalance(),
                    kind == SmsCreditLedgerKind.GRANT ? "grant" : "purchase"));
        } catch (RuntimeException ex) {
            log.warn("Failed to publish SMS credits realtime event: {}", ex.getMessage());
        }
    }

    private void publishAudit(
            String eventType,
            String businessId,
            int credits,
            String referenceId,
            String actorUserId
    ) {
        try {
            auditEventPublisher.publish(auditEventBuilder.builder(
                            AuditEventCategory.SYSTEM,
                            eventType,
                            AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(actorUserId,
                            actorUserId != null && !actorUserId.isBlank()
                                    ? AuditEventActorType.USER
                                    : AuditEventActorType.SYSTEM)
                    .target("sms_credits", businessId)
                    .targetLabel(credits + " credits" + (referenceId != null ? " (" + referenceId + ")" : ""))
                    .source("sms_credits")
                    .build());
        } catch (RuntimeException ex) {
            // Audit must never break the credit movement that already committed.
            log.warn("Failed to publish SMS credits audit event: {}", ex.getMessage());
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SmsCreditService.class);
}
