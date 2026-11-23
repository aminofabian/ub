package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.wholesale.api.dto.PortalOverviewResponse;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.NextThing;

/**
 * Standing, milestones and the next thing for a supplier in the portal (decision D2), with or without a shop.
 * A portal supplier has no trade orders of its own; what it has is the purchase orders shops send it, with the
 * time each was sent and the time it was answered. So this says how quickly it answers, over the same window and
 * with the same floor as a shop's standing, and nothing it cannot know: the portal keeps no dispatch time, so
 * there is no on-time measure here. Read-only, and off unless platform staff switched it on for the supplier.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalOverviewService {

    static final int WINDOW_DAYS = SupplierStandingService.DEFAULT_WINDOW_DAYS;

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Transactional(readOnly = true)
    public PortalOverviewResponse overview(String marketplaceSupplierId) {
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(marketplaceSupplierId).orElse(null);
        if (killSwitch || passport == null || !passport.isEngagementEnabled()) {
            return PortalOverviewResponse.off();
        }
        List<PurchaseOrder> sent = purchaseOrderRepository.findSupplierPortalInbox(marketplaceSupplierId).stream()
                .filter(po -> po.getSentToSupplierAt() != null)
                .toList();
        return describe(sent, Instant.now());
    }

    static PortalOverviewResponse describe(List<PurchaseOrder> sent, Instant now) {
        List<PurchaseOrder> waiting = sent.stream().filter(po -> po.getSupplierResponseAt() == null).toList();
        Instant oldest = waiting.stream()
                .map(PurchaseOrder::getSentToSupplierAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        NextThing next = waiting.isEmpty()
                ? null
                : new NextThing(
                        waiting.size() == 1
                                ? "1 order is waiting for your answer"
                                : waiting.size() + " orders are waiting for your answer",
                        "/supplier-portal/orders");

        List<PurchaseOrder> answeredEver = sent.stream().filter(po -> po.getSupplierResponseAt() != null).toList();
        Instant since = now.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        List<PurchaseOrder> answered = answeredEver.stream()
                .filter(po -> !po.getSupplierResponseAt().isBefore(since))
                .toList();
        int shops = (int) answered.stream().map(PurchaseOrder::getBusinessId).distinct().count();
        boolean enough = answered.size() >= SupplierStandingService.MIN_ANSWERED
                && shops >= SupplierStandingService.MIN_BUYERS;
        BigDecimal median = enough
                ? WorkingHours.median(answered.stream()
                        .map(po -> WorkingHours.between(po.getSentToSupplierAt(), po.getSupplierResponseAt()))
                        .toList())
                : null;

        List<String> milestones = new ArrayList<>();
        if (answeredEver.size() >= 1) {
            milestones.add("First order answered");
        }
        if (answeredEver.size() >= 10) {
            milestones.add("10 orders answered");
        }
        if (answeredEver.size() >= 100) {
            milestones.add("100 orders answered");
        }
        long repeatShops = answeredEver.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PurchaseOrder::getBusinessId, java.util.stream.Collectors.counting()))
                .values().stream().filter(count -> count >= 2).count();
        if (repeatShops >= 1) {
            milestones.add("First shop to order from you again");
        }
        return new PortalOverviewResponse(
                true, next, waiting.size(), oldest, enough ? "measured" : "new",
                answered.size(), shops, median, List.copyOf(milestones));
    }
}
