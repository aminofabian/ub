package zelisline.ub.ai.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.PriceRadarResponse;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.pricing.api.dto.SellPriceSuggestionResponse;
import zelisline.ub.pricing.application.PricingService;

@Service
@RequiredArgsConstructor
public class PriceRadarService {

    private static final int SCALE = 2;

    private final SokoMindRuntimeService runtimeService;
    private final PricingService pricingService;
    private final ItemRepository itemRepository;
    private final GlobalProductRepository globalProductRepository;

    @Transactional(readOnly = true)
    public PriceRadarResponse radar(
            String businessId,
            String itemId,
            String supplierId,
            String branchId,
            BigDecimal draftUnitCost
    ) {
        if (!runtimeService.isBrainEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind Brain is disabled. Enable Brain in Super Admin → Platform → SokoMind.");
        }
        Item item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));

        SellPriceSuggestionResponse suggest = pricingService.suggestSellPrice(
                businessId, itemId, supplierId, branchId, draftUnitCost);

        BigDecimal globalBuy = null;
        BigDecimal globalSell = null;
        if (item.getGlobalProductSourceId() != null && !item.getGlobalProductSourceId().isBlank()) {
            GlobalProduct gp = globalProductRepository.findById(item.getGlobalProductSourceId()).orElse(null);
            if (gp != null) {
                globalBuy = gp.getRecommendedBuyingPrice();
                globalSell = gp.getRecommendedSellingPrice();
            }
        }

        List<BigDecimal> bandPoints = new ArrayList<>();
        if (suggest.suggestedSellPrice() != null) {
            bandPoints.add(suggest.suggestedSellPrice());
        }
        if (globalSell != null && globalSell.signum() > 0) {
            bandPoints.add(globalSell);
        }
        if (suggest.currentSellPrice() != null && suggest.currentSellPrice().signum() > 0) {
            bandPoints.add(suggest.currentSellPrice());
        }
        if (suggest.latestUnitCost() != null && suggest.latestUnitCost().signum() > 0) {
            // Soft floor: cost * 1.05 so band isn't below near-cost
            bandPoints.add(suggest.latestUnitCost().multiply(new BigDecimal("1.05")).setScale(SCALE, RoundingMode.HALF_UP));
        }

        BigDecimal bandLow = min(bandPoints);
        BigDecimal bandHigh = max(bandPoints);
        BigDecimal bandMid = mid(bandLow, bandHigh);

        List<String> signals = new ArrayList<>();
        if (suggest.latestUnitCost() != null) {
            signals.add("cost=" + money(suggest.latestUnitCost()));
        }
        if (suggest.suggestedSellPrice() != null) {
            signals.add("ruleSuggest=" + money(suggest.suggestedSellPrice()));
        }
        if (globalSell != null) {
            signals.add("globalRecommendSell=" + money(globalSell));
        }
        if (suggest.currentSellPrice() != null) {
            signals.add("currentSell=" + money(suggest.currentSellPrice()));
        }

        String stance;
        String rationale;
        BigDecimal current = suggest.currentSellPrice();
        if (current == null || current.signum() <= 0) {
            stance = "missing";
            rationale = suggest.suggestedSellPrice() != null
                    ? "No shelf price set. Rule suggests " + money(suggest.suggestedSellPrice()) + "."
                    : "No shelf price and no rule suggestion yet.";
        } else if (bandLow != null && current.compareTo(bandLow) < 0) {
            stance = "below_band";
            rationale = "Current sell is below the Price Radar band ("
                    + money(bandLow) + "–" + money(bandHigh) + "). Consider raising toward rule/global mid.";
        } else if (bandHigh != null && current.compareTo(bandHigh) > 0) {
            stance = "above_band";
            rationale = "Current sell is above the Price Radar band ("
                    + money(bandLow) + "–" + money(bandHigh) + "). Check if margin is intentional.";
        } else if (suggest.suggestedSellPrice() != null
                && current.subtract(suggest.suggestedSellPrice()).abs().compareTo(new BigDecimal("1.00")) <= 0) {
            stance = "on_target";
            rationale = "Current sell is aligned with the margin rule suggestion.";
        } else {
            stance = "in_band";
            rationale = "Current sell sits inside the radar band"
                    + (bandMid != null ? " (mid ≈ " + money(bandMid) + ")" : "")
                    + ".";
        }

        if (suggest.latestUnitCost() != null
                && current != null
                && current.compareTo(suggest.latestUnitCost()) <= 0) {
            stance = "at_or_below_cost";
            rationale = "Current sell is at or below cost — margin leak risk.";
            signals.add("marginLeak");
        }

        return new PriceRadarResponse(
                item.getId(),
                item.getName(),
                suggest.latestUnitCost(),
                suggest.currentSellPrice(),
                suggest.suggestedSellPrice(),
                suggest.marginPercent(),
                suggest.ruleName(),
                globalBuy,
                globalSell,
                bandLow,
                bandMid,
                bandHigh,
                stance,
                rationale,
                signals,
                suggest.note());
    }

    private static BigDecimal min(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal max(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal mid(BigDecimal low, BigDecimal high) {
        if (low == null || high == null) {
            return low != null ? low : high;
        }
        return low.add(high).divide(new BigDecimal("2"), SCALE, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
