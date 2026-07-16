package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.api.dto.PosQuickCreateItemRequest;
import zelisline.ub.sales.api.dto.PosQuickCreateVariantLine;
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

        boolean asGroup = Boolean.TRUE.equals(req.createAsGroup());
        if (asGroup) {
            return createGroupWithVariants(businessId, actorUserId, req, branchId, idempotencyKey);
        }

        if (req.unitPrice() == null || req.unitPrice().signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Sell price is required"
            );
        }

        ItemResponse item;
        String relatedId = blankToNull(req.relatedItemId());
        if (relatedId != null) {
            item = createAsVariant(businessId, actorUserId, req, relatedId);
        } else {
            item = createStandalone(businessId, actorUserId, req, idempotencyKey);
        }

        priceAndStock(
                businessId,
                actorUserId,
                branchId,
                item.id(),
                req.unitPrice(),
                req.buyingPrice(),
                req.initialStockQty()
        );

        return item;
    }

    private ItemResponse createGroupWithVariants(
            String businessId,
            String actorUserId,
            PosQuickCreateItemRequest req,
            String branchId,
            String idempotencyKey
    ) {
        if (blankToNull(req.relatedItemId()) != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot link to an existing product when creating a new group"
            );
        }
        List<PosQuickCreateVariantLine> lines = req.variants();
        if (lines == null || lines.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add at least one variant for the group"
            );
        }

        String groupSku = "POS-G-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        CreateItemRequest parentReq = new CreateItemRequest(
                groupSku,
                null,
                req.name().trim(),
                null,
                req.itemTypeId().trim(),
                blankToNull(req.categoryId()),
                null,
                "each",
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
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

        ItemResponse parent = itemCatalogService.createItem(
                businessId,
                parentReq,
                idempotencyKey,
                actorUserId
        ).body();

        ItemResponse first = null;
        for (PosQuickCreateVariantLine line : lines) {
            String optionLabel = line.variantName().trim();
            CreateVariantRequest variantReq = new CreateVariantRequest(
                    "POS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                    optionLabel,
                    blankToNull(line.barcode()),
                    req.name().trim() + " — " + optionLabel,
                    null,
                    blankToNull(req.categoryId()),
                    null,
                    blankToNull(req.unitType()) != null ? req.unitType().trim() : "each",
                    false,
                    true,
                    true,
                    false,
                    null,
                    null,
                    null,
                    null,
                    line.buyingPrice(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            ItemResponse created = itemCatalogService.createVariant(
                    businessId,
                    parent.id(),
                    variantReq,
                    actorUserId
            );
            priceAndStock(
                    businessId,
                    actorUserId,
                    branchId,
                    created.id(),
                    line.unitPrice(),
                    line.buyingPrice(),
                    line.initialStockQty()
            );
            if (first == null) {
                first = created;
            }
        }
        return first;
    }

    private void priceAndStock(
            String businessId,
            String actorUserId,
            String branchId,
            String itemId,
            BigDecimal unitPrice,
            BigDecimal buyingPrice,
            BigDecimal initialStockQty
    ) {
        pricingService.setSellingPrice(
                businessId,
                new PostSellingPriceRequest(
                        itemId,
                        branchId,
                        unitPrice,
                        LocalDate.now(),
                        "POS cashier quick-create"
                ),
                actorUserId
        );

        BigDecimal openingQty = initialStockQty != null
                ? initialStockQty
                : DEFAULT_OPENING_QTY;
        BigDecimal unitCost = resolveUnitCost(buyingPrice);
        inventoryLedgerService.recordOpeningBalance(
                businessId,
                new PostOpeningBalanceRequest(
                        branchId,
                        itemId,
                        openingQty,
                        unitCost,
                        "POS cashier quick-create opening stock"
                ),
                actorUserId
        );
    }

    private ItemResponse createStandalone(
            String businessId,
            String actorUserId,
            PosQuickCreateItemRequest req,
            String idempotencyKey
    ) {
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

        return itemCatalogService.createItem(
                businessId,
                createReq,
                idempotencyKey,
                actorUserId
        ).body();
    }

    private ItemResponse createAsVariant(
            String businessId,
            String actorUserId,
            PosQuickCreateItemRequest req,
            String relatedItemId
    ) {
        ItemResponse related = itemCatalogService.getItem(businessId, relatedItemId);
        // Parent → child; existing variant → sibling under the same parent.
        String parentId = blankToNull(related.variantOfItemId()) != null
                ? related.variantOfItemId()
                : related.id();

        String optionLabel = blankToNull(req.variantName());
        if (optionLabel == null) {
            optionLabel = req.name().trim();
        }

        CreateVariantRequest variantReq = new CreateVariantRequest(
                "POS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                optionLabel,
                blankToNull(req.barcode()),
                req.name().trim(),
                null,
                blankToNull(req.categoryId()),
                null,
                blankToNull(req.unitType()) != null ? req.unitType().trim() : "each",
                false,
                true,
                true,
                false,
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
                null
        );

        return itemCatalogService.createVariant(
                businessId,
                parentId,
                variantReq,
                actorUserId
        );
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
