package zelisline.ub.ai.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** L0–L2 Price Radar: rule suggest + global catalog band + simple guidance. */
public record PriceRadarResponse(
        String itemId,
        String itemName,
        BigDecimal cost,
        BigDecimal currentSell,
        BigDecimal ruleSuggestedSell,
        BigDecimal marginPercent,
        String ruleName,
        BigDecimal globalRecommendedBuy,
        BigDecimal globalRecommendedSell,
        BigDecimal bandLow,
        BigDecimal bandMid,
        BigDecimal bandHigh,
        String stance,
        String rationale,
        List<String> signals,
        String note
) {}
