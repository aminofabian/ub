package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import zelisline.ub.billing.domain.PlatformSubscriptionPlan;

/**
 * Renewal pricing — monthly linear or annual discount (10 months for 12).
 */
@Service
public class SubscriptionPricingService {

    public static final int ANNUAL_PERIOD_MONTHS = 12;

    public BigDecimal resolveRenewalAmount(PlatformSubscriptionPlan plan, int periodMonths) {
        if (plan == null) {
            return BigDecimal.ZERO;
        }
        if (periodMonths == ANNUAL_PERIOD_MONTHS) {
            return resolveAnnualAmount(plan);
        }
        if (periodMonths < 1) {
            return BigDecimal.ZERO;
        }
        return plan.getMonthlyPriceKes()
                .multiply(BigDecimal.valueOf(periodMonths))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal resolveAnnualAmount(PlatformSubscriptionPlan plan) {
        if (plan.getAnnualPriceKes() != null && plan.getAnnualPriceKes().signum() > 0) {
            return plan.getAnnualPriceKes().setScale(2, RoundingMode.HALF_UP);
        }
        return plan.getMonthlyPriceKes()
                .multiply(BigDecimal.TEN)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal annualListPrice(PlatformSubscriptionPlan plan) {
        return plan.getMonthlyPriceKes()
                .multiply(BigDecimal.valueOf(ANNUAL_PERIOD_MONTHS))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal annualSavings(PlatformSubscriptionPlan plan) {
        BigDecimal list = annualListPrice(plan);
        BigDecimal annual = resolveAnnualAmount(plan);
        BigDecimal savings = list.subtract(annual);
        return savings.signum() > 0 ? savings : BigDecimal.ZERO;
    }
}
