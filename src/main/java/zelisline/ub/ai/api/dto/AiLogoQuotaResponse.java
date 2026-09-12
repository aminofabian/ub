package zelisline.ub.ai.api.dto;

import java.math.BigDecimal;

/**
 * Tenant view of AI brand-kit quota: free allowance remaining and credit cost
 * after that (purchased SMS credits).
 */
public record AiLogoQuotaResponse(
        int freeAllowance,
        int freeUsed,
        int freeRemaining,
        int creditCost,
        int purchasedCredits,
        boolean nextGenerationIsFree,
        boolean canGenerate,
        BigDecimal unitPriceKes,
        int minPurchaseCredits,
        int maxPurchaseCredits
) {
}
