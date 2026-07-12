package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.api.dto.PosQuickCreateItemRequest;
import zelisline.ub.tenancy.application.FeatureFlagService;

@Service
@RequiredArgsConstructor
public class PosQuickCreateItemService {

    private static final String CATALOG_WRITE = "catalog.items.write";
    private static final String PRICING_SET = "pricing.sell_price.set";
    private static final BigDecimal DEFAULT_OPENING_QTY = BigDecimal.ONE;
    private static final BigDecimal MIN_UNIT_COST = new BigDecimal("0.01");

    private final FeatureFlagService featureFlagService;
    private final RequestPermissionService requestPermissionService;
    private final ItemCatalogService itemCatalogService;
    private final PricingService pricingService;
    private final InventoryLedgerService inventoryLedgerService;

    @Transactional
    public ItemResponse create(
            String businessId,
            String roleId,
            String actorUserId,
            PosQuickCreateItemRequest req,
            String idempotencyKey
    ) {
        boolean canWrite = roleId != null
                && requestPermissionService.hasPermission(roleId, CATALOG_WRITE);
        boolean flagOn = featureFlagService.isEnabled(
                businessId,
                FeatureFlagService.FLAG_POS_CASHIER_CREATE_PRODUCT
        );
        if (!canWrite && !flagOn) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Creating products from cashier is not enabled"
            );
        }

        boolean canSetPrice = roleId != null
                && requestPermissionService.hasPermission(roleId, PRICING_SET);
        boolean priceFlagOn = featureFlagService.isEnabled(
                businessId,
                FeatureFlagService.FLAG_POS_CASHIER_PRICE_EDIT
        );
        if (!canSetPrice && !priceFlagOn && !flagOn) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Setting a sell price from cashier is not enabled"
            );
        }

        String branchId = blankToNull(req.branchId());
        if (branchId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Select a branch before adding a product — stock is received at the till branch"
            );
        }

        // Dedicated unique SKU — avoids colliding with soft-deleted "SKU-xxxxx" rows
        // that still occupy the business_id+sku unique index.
        String sku = "POS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        CreateItemRequest createReq = new CreateItemRequest(
                sku,
                blankToNull(req.barcode()),
                req.name().trim(),
                null,
                req.itemTypeId().trim(),
                blankToNull(req.categoryId()),
                null,
                blankToNull(req.unitType()) != null ? req.unitType().trim() : "each",
                false,
                true,
                true,
                null,
                null,
                null,
                null,
                req.buyingPrice(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        var result = itemCatalogService.createItem(
                businessId,
                createReq,
                idempotencyKey,
                actorUserId
        );
        ItemResponse item = result.body();

        pricingService.setSellingPrice(
                businessId,
                new PostSellingPriceRequest(
                        item.id(),
                        branchId,
                        req.unitPrice(),
                        LocalDate.now(),
                        "POS cashier quick-create"
                ),
                actorUserId
        );

        // Opening batch so checkout can allocate cost/qty immediately.
        BigDecimal openingQty = req.initialStockQty() != null
                ? req.initialStockQty()
                : DEFAULT_OPENING_QTY;
        BigDecimal unitCost = resolveUnitCost(req.buyingPrice());
        inventoryLedgerService.recordOpeningBalance(
                businessId,
                new PostOpeningBalanceRequest(
                        branchId,
                        item.id(),
                        openingQty,
                        unitCost,
                        "POS cashier quick-create opening stock"
                ),
                actorUserId
        );

        return item;
    }

    private static BigDecimal resolveUnitCost(BigDecimal buyingPrice) {
        if (buyingPrice == null || buyingPrice.signum() <= 0) {
            return MIN_UNIT_COST;
        }
        return buyingPrice.setScale(4, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
