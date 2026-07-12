package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.api.dto.PosQuickCreateItemRequest;
import zelisline.ub.tenancy.application.FeatureFlagService;

@Service
@RequiredArgsConstructor
public class PosQuickCreateItemService {

    private static final String CATALOG_WRITE = "catalog.items.write";
    private static final String PRICING_SET = "pricing.sell_price.set";

    private final FeatureFlagService featureFlagService;
    private final RequestPermissionService requestPermissionService;
    private final ItemCatalogService itemCatalogService;
    private final PricingService pricingService;

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
            // Creating a sellable POS item without a shelf price is not useful;
            // allow when create-product flag is on (sets price as part of create).
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Setting a sell price from cashier is not enabled"
            );
        }

        CreateItemRequest createReq = new CreateItemRequest(
                null,
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

        var result = itemCatalogService.createItem(
                businessId,
                createReq,
                idempotencyKey,
                actorUserId
        );
        ItemResponse item = result.body();

        String branchId = blankToNull(req.branchId());
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

        return item;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
