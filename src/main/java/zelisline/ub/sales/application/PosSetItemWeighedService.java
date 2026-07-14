package zelisline.ub.sales.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.sales.api.dto.PosSetItemWeighedRequest;
import zelisline.ub.tenancy.application.FeatureFlagService;

@Service
@RequiredArgsConstructor
public class PosSetItemWeighedService {

    private static final String CATALOG_WRITE = "catalog.items.write";

    private final FeatureFlagService featureFlagService;
    private final RequestPermissionService requestPermissionService;
    private final ItemCatalogService itemCatalogService;

    @Transactional
    public ItemResponse setWeighed(
            String businessId,
            String roleId,
            String actorUserId,
            String itemId,
            PosSetItemWeighedRequest req
    ) {
        boolean canWrite = roleId != null
                && requestPermissionService.hasPermission(roleId, CATALOG_WRITE);
        boolean flagOn = featureFlagService.isEnabled(
                businessId,
                FeatureFlagService.FLAG_POS_CASHIER_WEIGHED_TOGGLE
        );
        if (!canWrite && !flagOn) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Marking items as weighted from cashier is not enabled"
            );
        }

        String id = itemId == null ? "" : itemId.trim();
        if (id.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item id is required");
        }

        boolean weighed = Boolean.TRUE.equals(req.weighed());
        // Sale API currently accepts weighed qty in kg only.
        String nextUnit = weighed ? "kg" : null;

        return itemCatalogService.patchItem(
                businessId,
                id,
                new PatchItemRequest(
                        null, // expectedUpdatedAt
                        null, // sku
                        null, // barcode
                        null, // name
                        null, // description
                        null, // categoryId
                        null, // aisleId
                        null, // itemTypeId
                        nextUnit,
                        weighed,
                        null, // isSellable
                        null, // isStocked
                        null, // packageVariant
                        null, // packagingUnitName
                        null, // packagingUnitQty
                        null, // bundleQty
                        null, // bundlePrice
                        null, // buyingPrice
                        null, // bundleName
                        null, // minStockLevel
                        null, // reorderLevel
                        null, // reorderQty
                        null, // expiresAfterDays
                        null, // hasExpiry
                        null, // imageKey
                        null, // active
                        null, // webPublished
                        null, // brand
                        null, // size
                        null, // variantName
                        null // pluCode
                ),
                actorUserId
        );
    }
}
