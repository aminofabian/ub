package zelisline.ub.ai.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.AiLogoQuotaResponse;
import zelisline.ub.ai.domain.BusinessAiLogoUsage;
import zelisline.ub.ai.repository.BusinessAiLogoUsageRepository;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.messaging.application.SmsCreditSettingsService;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsSendReason;

/**
 * Meters AI brand-kit generation: each shop gets a free allowance (default 1),
 * then pays {@code ai_logo_credit_cost} purchased credits per kit (default 50).
 *
 * <p>Free slots are reserved under a row lock before the image provider runs, so
 * concurrent generates cannot all claim the free kit. On provider failure the
 * free reservation is released. Paid kits are checked before generate and
 * debited only after success.
 */
@Service
@RequiredArgsConstructor
public class AiLogoCreditService {

    private final BusinessAiLogoUsageRepository usageRepository;
    private final SmsCreditService smsCreditService;
    private final SmsCreditSettingsService settingsService;

    public record Reservation(boolean free) {
    }

    @Transactional(readOnly = true)
    public AiLogoQuotaResponse quota(String businessId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        int allowance = Math.max(0, settings.getAiLogoFreeAllowance());
        int cost = Math.max(1, settings.getAiLogoCreditCost());
        BusinessAiLogoUsage usage = usageRepository.findByBusinessId(businessId).orElse(null);
        int freeUsed = usage != null ? usage.getFreeUsed() : 0;
        int freeRemaining = Math.max(0, allowance - freeUsed);
        int purchased = smsCreditService.getBalanceView(businessId).purchasedBalance();
        boolean nextFree = freeRemaining > 0;
        boolean canGenerate = nextFree || purchased >= cost;
        return new AiLogoQuotaResponse(
                allowance,
                freeUsed,
                freeRemaining,
                cost,
                purchased,
                nextFree,
                canGenerate,
                settings.getUnitPriceKes(),
                settings.getMinPurchaseCredits(),
                settings.getMaxPurchaseCredits());
    }

    /**
     * Lock the usage row and either consume a free slot or verify purchased
     * credits cover the cost. Call {@link #releaseFree} if the provider fails
     * after a free reservation; call {@link #commitPaid} after a paid success.
     */
    @Transactional
    public Reservation reserve(String businessId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        int allowance = Math.max(0, settings.getAiLogoFreeAllowance());
        int cost = Math.max(1, settings.getAiLogoCreditCost());
        BusinessAiLogoUsage usage = lockOrCreate(businessId);
        if (usage.getFreeUsed() < allowance) {
            usage.setFreeUsed(usage.getFreeUsed() + 1);
            usageRepository.save(usage);
            return new Reservation(true);
        }
        smsCreditService.requirePurchasedAvailable(businessId, cost);
        return new Reservation(false);
    }

    /** Undo a free reservation when image generation fails. */
    @Transactional
    public void releaseFree(String businessId) {
        BusinessAiLogoUsage usage = usageRepository.findForUpdate(businessId).orElse(null);
        if (usage == null || usage.getFreeUsed() <= 0) {
            return;
        }
        usage.setFreeUsed(usage.getFreeUsed() - 1);
        usageRepository.save(usage);
    }

    /** Debit purchased credits after a paid generation succeeds. */
    @Transactional
    public void commitPaid(String businessId, String requestId) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        int cost = Math.max(1, settings.getAiLogoCreditCost());
        smsCreditService.debitPurchased(businessId, cost, SmsSendReason.AI_LOGO, requestId);
        BusinessAiLogoUsage usage = lockOrCreate(businessId);
        usage.setPaidCount(usage.getPaidCount() + 1);
        usageRepository.save(usage);
    }

    private BusinessAiLogoUsage lockOrCreate(String businessId) {
        return usageRepository.findForUpdate(businessId)
                .orElseGet(() -> {
                    BusinessAiLogoUsage created = new BusinessAiLogoUsage();
                    created.setBusinessId(businessId);
                    created.setFreeUsed(0);
                    created.setPaidCount(0);
                    try {
                        usageRepository.saveAndFlush(created);
                    } catch (DataIntegrityViolationException ex) {
                        return usageRepository.findForUpdate(businessId)
                                .orElseThrow(() -> ex);
                    }
                    return usageRepository.findForUpdate(businessId).orElse(created);
                });
    }
}
