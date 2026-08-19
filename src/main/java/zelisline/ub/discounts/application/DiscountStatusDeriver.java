package zelisline.ub.discounts.application;

import java.time.Instant;

import org.springframework.stereotype.Component;

import zelisline.ub.discounts.domain.Discount;
import zelisline.ub.discounts.domain.DiscountStatuses;

@Component
public class DiscountStatusDeriver {

    public String deriveStatus(Discount discount, Instant now) {
        if (discount.getPublishedAt() == null) {
            return DiscountStatuses.DRAFT;
        }
        if (discount.getEndAt() != null && !now.isBefore(discount.getEndAt())) {
            return DiscountStatuses.EXPIRED;
        }
        if (discount.isPaused()) {
            return DiscountStatuses.PAUSED;
        }
        if (now.isBefore(discount.getStartAt())) {
            return DiscountStatuses.SCHEDULED;
        }
        return DiscountStatuses.ACTIVE;
    }
}
