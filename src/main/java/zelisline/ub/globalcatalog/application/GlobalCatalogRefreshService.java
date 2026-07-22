package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogLineResponse;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogRequest;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.pricing.api.dto.PostBuyingPriceRequest;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Shop-opt-in apply of template recommended sell/buy/image onto already-adopted items.
 * Defaults: no field updates unless flags are true; selling skips customized prices.
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogRefreshService {

    public static final int MAX_REFRESH_IDS = 50;

    private static final int MONEY_SCALE = 2;

    private final GlobalCatalogResolver globalCatalogResolver;
    private final GlobalProductRepository globalProductRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final SellingPriceRepository sellingPriceRepository;
    private final PricingService pricingService;
    private final SupplierProductRepository supplierProductRepository;
    private final GlobalCatalogAdoptImageAttacher imageAttacher;

    @Transactional(readOnly = true)
    public RefreshCatalogResponse preview(String businessId, RefreshCatalogRequest request) {
        return run(businessId, request, true, null);
    }

    @Transactional
    public RefreshCatalogResponse refresh(String businessId, RefreshCatalogRequest request, String actorUserId) {
        return run(businessId, request, false, actorUserId);
    }

    private RefreshCatalogResponse run(
            String businessId,
            RefreshCatalogRequest request,
            boolean dryRun,
            String actorUserId
    ) {
        requireBranch(businessId, request.branchId());
        List<String> ids = uniqueIds(request.globalProductIds());
        if (ids.size() > MAX_REFRESH_IDS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Refresh limited to " + MAX_REFRESH_IDS + " products");
        }

        boolean refreshSell = Boolean.TRUE.equals(request.refreshSellingPrice());
        boolean refreshBuy = Boolean.TRUE.equals(request.refreshBuyingPrice());
        boolean refreshImage = Boolean.TRUE.equals(request.refreshImage());
        boolean forceImage = Boolean.TRUE.equals(request.forceImage());
        boolean skipCustomized = Boolean.TRUE.equals(request.skipCustomizedSellingPrice());

        if (!refreshSell && !refreshBuy && !refreshImage) {
            List<RefreshCatalogLineResponse> noop = ids.stream()
                    .map(id -> line(id, null, "skipped", "No refresh flags enabled", null, null, null, null,
                            false, false, false))
                    .toList();
            return new RefreshCatalogResponse(0, noop.size(), noop);
        }

        GlobalCatalog catalog = globalCatalogResolver.resolveForBusiness(businessId);
        Map<String, GlobalProduct> products = loadPublishedProducts(catalog.getId(), ids);
        List<Item> items = itemRepository.findByBusinessIdAndGlobalProductSourceIdInAndDeletedAtIsNull(
                businessId, ids);
        Map<String, Item> itemByGlobalId = new HashMap<>();
        for (Item item : items) {
            itemByGlobalId.putIfAbsent(item.getGlobalProductSourceId(), item);
        }

        List<RefreshCatalogLineResponse> lines = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for (String globalId : ids) {
            GlobalProduct gp = products.get(globalId);
            if (gp == null) {
                lines.add(line(globalId, null, "skipped", "Global product not found or not published",
                        null, null, null, null, false, false, false));
                skipped++;
                continue;
            }
            Item item = itemByGlobalId.get(globalId);
            if (item == null) {
                lines.add(line(globalId, null, "skipped", "Not adopted in this shop",
                        null, gp.getRecommendedSellingPrice(), null, gp.getRecommendedBuyingPrice(),
                        false, false, false));
                skipped++;
                continue;
            }

            BigDecimal currentSell = currentSellingPrice(businessId, item.getId(), request.branchId());
            BigDecimal recommendedSell = gp.getRecommendedSellingPrice();
            BigDecimal currentBuy = item.getBuyingPrice();
            BigDecimal recommendedBuy = gp.getRecommendedBuyingPrice();

            boolean sellUpdated = false;
            boolean buyUpdated = false;
            boolean imageUpdated = false;
            List<String> messages = new ArrayList<>();

            if (refreshSell) {
                SellDecision decision = decideSell(currentSell, recommendedSell, skipCustomized);
                if (decision.shouldApply()) {
                    if (!dryRun) {
                        pricingService.setSellingPrice(
                                businessId,
                                new PostSellingPriceRequest(
                                        item.getId(),
                                        request.branchId(),
                                        recommendedSell,
                                        LocalDate.now(),
                                        "Global catalog refresh"),
                                actorUserId
                        );
                    }
                    sellUpdated = true;
                    messages.add(dryRun ? "Would update selling price" : "Selling price updated");
                } else {
                    messages.add(decision.reason());
                }
            }

            if (refreshBuy) {
                BuyDecision decision = decideBuy(currentBuy, recommendedBuy);
                if (decision.shouldApply()) {
                    if (!dryRun) {
                        applyBuying(businessId, item, recommendedBuy, actorUserId);
                    }
                    buyUpdated = true;
                    messages.add(dryRun ? "Would update buying price" : "Buying price updated");
                } else {
                    messages.add(decision.reason());
                }
            }

            if (refreshImage) {
                String imageUrl = blankToNull(gp.getImageUrl());
                if (imageUrl == null) {
                    messages.add("No template image");
                } else if (!dryRun) {
                    GlobalCatalogAdoptImageAttacher.AttachResult result = imageAttacher.attachFromGlobalUrl(
                            businessId,
                            item.getId(),
                            imageUrl,
                            !forceImage
                    );
                    if (result.attached()) {
                        imageUpdated = true;
                        messages.add("Image updated");
                    } else if (blankToNull(result.warning()) != null) {
                        messages.add(result.warning());
                    } else {
                        messages.add(forceImage ? "Image unchanged" : "Cover already present");
                    }
                } else {
                    boolean missingCover = blankToNull(item.getImageKey()) == null;
                    if (forceImage || missingCover) {
                        imageUpdated = true;
                        messages.add(forceImage ? "Would refresh image" : "Would set missing cover");
                    } else {
                        messages.add("Cover already present");
                    }
                }
            }

            boolean anyUpdate = sellUpdated || buyUpdated || imageUpdated;
            if (anyUpdate) {
                updated++;
            } else {
                skipped++;
            }
            lines.add(line(
                    globalId,
                    item.getId(),
                    anyUpdate ? (dryRun ? "would_update" : "updated") : "skipped",
                    String.join("; ", messages),
                    currentSell,
                    recommendedSell,
                    currentBuy,
                    recommendedBuy,
                    sellUpdated,
                    buyUpdated,
                    imageUpdated
            ));
        }

        return new RefreshCatalogResponse(updated, skipped, lines);
    }

    private void applyBuying(String businessId, Item item, BigDecimal recommendedBuy, String actorUserId) {
        item.setBuyingPrice(recommendedBuy);
        itemRepository.save(item);

        SupplierProduct primary = supplierProductRepository.listForItem(businessId, item.getId()).stream()
                .filter(SupplierProduct::isActive)
                .filter(SupplierProduct::isPrimaryLink)
                .findFirst()
                .orElse(null);
        if (primary == null) {
            return;
        }
        primary.setDefaultCostPrice(recommendedBuy);
        supplierProductRepository.save(primary);
        pricingService.setBuyingPrice(
                businessId,
                new PostBuyingPriceRequest(
                        item.getId(),
                        primary.getSupplierId(),
                        recommendedBuy,
                        LocalDate.now(),
                        "global_catalog_refresh",
                        "Global catalog refresh"),
                actorUserId
        );
    }

    private Map<String, GlobalProduct> loadPublishedProducts(String catalogId, List<String> ids) {
        Map<String, GlobalProduct> out = new HashMap<>();
        for (GlobalProduct gp : globalProductRepository.findAllById(ids)) {
            if (!catalogId.equals(gp.getCatalogId())) {
                continue;
            }
            if (!GlobalProductStatus.PUBLISHED.equals(gp.getStatus())) {
                continue;
            }
            out.put(gp.getId(), gp);
        }
        return out;
    }

    private BigDecimal currentSellingPrice(String businessId, String itemId, String branchId) {
        List<SellingPrice> open = sellingPriceRepository.findOpenEnded(businessId, itemId, branchId);
        if (open.isEmpty()) {
            open = sellingPriceRepository.findOpenEnded(businessId, itemId, null);
        }
        if (open.isEmpty()) {
            return null;
        }
        return open.get(0).getPrice();
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private static List<String> uniqueIds(List<String> raw) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String id : raw) {
            if (id == null || id.isBlank() || !seen.add(id.trim())) {
                continue;
            }
            out.add(id.trim());
        }
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No global product ids provided");
        }
        return out;
    }

    private static SellDecision decideSell(
            BigDecimal current,
            BigDecimal recommended,
            boolean skipCustomized
    ) {
        if (recommended == null || recommended.compareTo(BigDecimal.ZERO) <= 0) {
            return SellDecision.skip("No recommended selling price");
        }
        if (current != null && moneyEquals(current, recommended)) {
            return SellDecision.skip("Selling price already matches template");
        }
        if (skipCustomized && current != null && !moneyEquals(current, recommended)) {
            return SellDecision.skip("Selling price customized; skipped");
        }
        return SellDecision.ok();
    }

    private static BuyDecision decideBuy(BigDecimal current, BigDecimal recommended) {
        if (recommended == null || recommended.compareTo(BigDecimal.ZERO) <= 0) {
            return BuyDecision.skip("No recommended buying price");
        }
        if (current != null && moneyEquals(current, recommended)) {
            return BuyDecision.skip("Buying price already matches template");
        }
        return BuyDecision.ok();
    }

    private static boolean moneyEquals(BigDecimal a, BigDecimal b) {
        return a.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .compareTo(b.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0;
    }

    private static RefreshCatalogLineResponse line(
            String globalProductId,
            String itemId,
            String status,
            String message,
            BigDecimal currentSell,
            BigDecimal recommendedSell,
            BigDecimal currentBuy,
            BigDecimal recommendedBuy,
            boolean sellUpdated,
            boolean buyUpdated,
            boolean imageUpdated
    ) {
        return new RefreshCatalogLineResponse(
                globalProductId,
                itemId,
                status,
                message,
                currentSell,
                recommendedSell,
                currentBuy,
                recommendedBuy,
                sellUpdated,
                buyUpdated,
                imageUpdated
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record SellDecision(boolean shouldApply, String reason) {
        static SellDecision ok() {
            return new SellDecision(true, null);
        }

        static SellDecision skip(String reason) {
            return new SellDecision(false, reason);
        }
    }

    private record BuyDecision(boolean shouldApply, String reason) {
        static BuyDecision ok() {
            return new BuyDecision(true, null);
        }

        static BuyDecision skip(String reason) {
            return new BuyDecision(false, reason);
        }
    }
}
