package zelisline.ub.wholesale.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SupplierContribution;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.SupplierContributionRepository;

/**
 * Personal contribution: counts of a person's own work, shown only to that person. Never part of standing, pay
 * or a comparison between people. Written after the order's own transaction has finished, in a transaction of
 * its own; a failure here is logged and dropped, so answering an order never depends on it.
 */
@Service
@RequiredArgsConstructor
public class SupplierContributionService {

    private static final Logger log = LoggerFactory.getLogger(SupplierContributionService.class);

    private final FeatureFlagService featureFlagService;
    private final SupplierContributionRepository contributionRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SupplierOrderableService orderableService;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    public boolean on(String businessId) {
        if (killSwitch) {
            return false;
        }
        // One read of the shop's flags, not one per flag: this is asked on every hooked save, in every shop.
        java.util.Map<String, Boolean> flags = featureFlagService.allFlags(businessId);
        return flags != null
                && Boolean.TRUE.equals(flags.get(FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT))
                && Boolean.TRUE.equals(flags.get(FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_CONTRIBUTION));
    }

    /** Call after an order was answered. Counts it for the person if the answer came within a working day. */
    @Transactional
    public void recordAnswered(String businessId, String userId, String orderId) {
        try {
            if (userId == null || userId.isBlank() || !on(businessId)) {
                return;
            }
            SalesOrder order = salesOrderRepository.findById(orderId).orElse(null);
            if (order == null || !businessId.equals(order.getBusinessId()) || order.getAnsweredAt() == null
                    || WorkingHours.between(order.getCreatedAt(), order.getAnsweredAt())
                            > SupplierStandingService.HOURS_PER_WORKING_DAY) {
                return;
            }
            if (contributionRepository.existsByBusinessIdAndUserIdAndRuleIdAndSourceTypeAndSourceId(
                    businessId, userId, SupplierContribution.RULE_ANSWERED_WITHIN_A_WORKING_DAY,
                    SupplierContribution.SOURCE_SALES_ORDER, orderId)) {
                return;
            }
            contributionRepository.save(new SupplierContribution(
                    businessId, userId, SupplierContribution.RULE_ANSWERED_WITHIN_A_WORKING_DAY,
                    SupplierContribution.SOURCE_SALES_ORDER, orderId));
        } catch (RuntimeException e) {
            log.warn("Could not record a contribution for order {}: {}", orderId, e.toString());
        }
    }

    @Transactional(readOnly = true)
    public long answeredWithinAWorkingDay(String businessId, String userId) {
        return contributionRepository.countByBusinessIdAndUserIdAndRuleId(
                businessId, userId, SupplierContribution.RULE_ANSWERED_WITHIN_A_WORKING_DAY);
    }

    /** More products than this in one save are not checked: the check prices each one. */
    static final int MAX_ITEMS_PER_SAVE = 200;

    /**
     * Call before a save that may make products orderable (a product edit, a price, stock coming in). Returns
     * the products that cannot be ordered yet, to hand to {@link #recordMadeOrderable} after the save. Null when
     * contribution is off for the shop, when there is nothing or too much to check, or when the check fails; the
     * caller then does nothing more, so a shop with the flag off pays one flag read and no other query.
     */
    public java.util.Set<String> notYetOrderable(String businessId, java.util.Collection<String> itemIds) {
        try {
            if (itemIds == null || itemIds.isEmpty() || itemIds.size() > MAX_ITEMS_PER_SAVE || !on(businessId)) {
                return null;
            }
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String itemId : itemIds) {
                if (itemId != null && !itemId.isBlank() && !orderableService.isOrderable(businessId, itemId)) {
                    out.add(itemId);
                }
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The same, for a goods receipt, which names purchase order lines and not products. */
    public java.util.Set<String> notYetOrderableForPurchaseOrderLines(
            String businessId, java.util.Collection<String> purchaseOrderLineIds) {
        try {
            if (purchaseOrderLineIds == null || purchaseOrderLineIds.isEmpty()
                    || purchaseOrderLineIds.size() > MAX_ITEMS_PER_SAVE || purchaseOrderLineRepository == null
                    || !on(businessId)) {
                return null;
            }
            java.util.Set<String> itemIds = new java.util.HashSet<>();
            purchaseOrderLineRepository.findAllById(purchaseOrderLineIds).forEach(line -> {
                if (line.getItemId() != null) {
                    itemIds.add(line.getItemId());
                }
            });
            return notYetOrderable(businessId, itemIds);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Set by Spring; absent in a unit test. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zelisline.ub.purchasing.repository.PurchaseOrderLineRepository purchaseOrderLineRepository;

    /**
     * Call after the save, with what {@link #notYetOrderable} returned before it. Each product that could not be
     * ordered then and can be now is counted for the person, once per product for the whole shop.
     */
    @Transactional
    public void recordMadeOrderable(String businessId, String userId, java.util.Set<String> wereNotOrderable) {
        if (wereNotOrderable == null || wereNotOrderable.isEmpty() || userId == null || userId.isBlank()) {
            return;
        }
        for (String itemId : wereNotOrderable) {
            try {
                if (!orderableService.isOrderable(businessId, itemId)
                        || contributionRepository.existsByBusinessIdAndRuleIdAndSourceTypeAndSourceId(
                                businessId, SupplierContribution.RULE_MADE_PRODUCT_ORDERABLE,
                                SupplierContribution.SOURCE_ITEM, itemId)) {
                    continue;
                }
                contributionRepository.save(new SupplierContribution(
                        businessId, userId, SupplierContribution.RULE_MADE_PRODUCT_ORDERABLE,
                        SupplierContribution.SOURCE_ITEM, itemId));
            } catch (RuntimeException e) {
                log.warn("Could not record a contribution for product {}: {}", itemId, e.toString());
            }
        }
    }

    @Transactional(readOnly = true)
    public long productsMadeOrderable(String businessId, String userId) {
        return contributionRepository.countByBusinessIdAndUserIdAndRuleId(
                businessId, userId, SupplierContribution.RULE_MADE_PRODUCT_ORDERABLE);
    }
}
