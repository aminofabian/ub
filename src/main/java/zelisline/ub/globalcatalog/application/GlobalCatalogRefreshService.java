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
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <p>Price writes run in one short transaction; image re-hosting (CDN I/O) runs afterwards, outside
 * any transaction, so a slow media store cannot hold a pooled database connection.
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
    private final TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true)
    public RefreshCatalogResponse preview(String businessId, RefreshCatalogRequest request) {
        return assemble(plan(businessId, request, true, null));
    }

    public RefreshCatalogResponse refresh(String businessId, RefreshCatalogRequest request, String actorUserId) {
        // Phase 1 — sell/buy writes inside one short transaction.
        List<LineState> lines = transactionTemplate.execute(status -> plan(businessId, request, false, actorUserId));
        // Phase 2 — image re-host outside the transaction (CDN latency must not pin a connection).
        applyImages(businessId, lines);
        return assemble(lines);
    }

    private List<LineState> plan(
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
            List<LineState> noop = new ArrayList<>(ids.size());
            for (String id : ids) {
                noop.add(LineState.skipped(id, dryRun, "No refresh flags enabled"));
            }
            return noop;
        }

        GlobalCatalog catalog = globalCatalogResolver.resolveForBusiness(businessId);
        Map<String, GlobalProduct> products = loadPublishedProducts(catalog.getId(), ids);
        List<Item> items = itemRepository.findByBusinessIdAndGlobalProductSourceIdInAndDeletedAtIsNull(
                businessId, ids);
        Map<String, Item> itemByGlobalId = new HashMap<>();
        for (Item item : items) {
            itemByGlobalId.putIfAbsent(item.getGlobalProductSourceId(), item);
        }

        List<LineState> out = new ArrayList<>(ids.size());
        for (String globalId : ids) {
            GlobalProduct gp = products.get(globalId);
            if (gp == null) {
                out.add(LineState.skipped(globalId, dryRun, "Global product not found or not published"));
                continue;
            }
            Item item = itemByGlobalId.get(globalId);
            if (item == null) {
                LineState st = LineState.skipped(globalId, dryRun, "Not adopted in this shop");
                st.recommendedSell = gp.getRecommendedSellingPrice();
                st.recommendedBuy = gp.getRecommendedBuyingPrice();
                out.add(st);
                continue;
            }

            LineState st = new LineState(globalId, item.getId(), dryRun);
            st.currentSell = currentSellingPrice(businessId, item.getId(), request.branchId());
            st.recommendedSell = gp.getRecommendedSellingPrice();
            st.currentBuy = item.getBuyingPrice();
            st.recommendedBuy = gp.getRecommendedBuyingPrice();

            if (refreshSell) {
                SellDecision decision = decideSell(st.currentSell, st.recommendedSell, skipCustomized);
                if (decision.shouldApply()) {
                    if (!dryRun) {
                        pricingService.setSellingPrice(
                                businessId,
                                new PostSellingPriceRequest(
                                        item.getId(),
                                        request.branchId(),
                                        st.recommendedSell,
                                        LocalDate.now(),
                                        "Global catalog refresh"),
                                actorUserId
                        );
                    }
                    st.sellUpdated = true;
                    st.messages.add(dryRun ? "Would update selling price" : "Selling price updated");
                } else {
                    st.messages.add(decision.reason());
                }
            }

            if (refreshBuy) {
                BuyDecision decision = decideBuy(st.currentBuy, st.recommendedBuy);
                if (decision.shouldApply()) {
                    if (!dryRun) {
                        applyBuying(businessId, item, st.recommendedBuy, actorUserId);
                    }
                    st.buyUpdated = true;
                    st.messages.add(dryRun ? "Would update buying price" : "Buying price updated");
                } else {
                    st.messages.add(decision.reason());
                }
            }

            if (refreshImage) {
                String imageUrl = blankToNull(gp.getImageUrl());
                if (imageUrl == null) {
                    st.messages.add("No template image");
                } else if (dryRun) {
                    boolean missingCover = blankToNull(item.getImageKey()) == null;
                    if (forceImage || missingCover) {
                        st.imageUpdated = true;
                        st.messages.add(forceImage ? "Would refresh image" : "Would set missing cover");
                    } else {
                        st.messages.add("Cover already present");
                    }
                } else {
                    // Deferred to phase 2 — no CDN call inside the transaction.
                    st.pendingImageUrl = imageUrl;
                    st.pendingImageOnlyIfMissing = !forceImage;
                }
            }

            out.add(st);
        }
        return out;
    }

    private void applyImages(String businessId, List<LineState> lines) {
        for (LineState st : lines) {
            if (st.pendingImageUrl == null) {
                continue;
            }
            GlobalCatalogAdoptImageAttacher.AttachResult result = imageAttacher.attachFromGlobalUrl(
                    businessId,
                    st.itemId,
                    st.pendingImageUrl,
                    st.pendingImageOnlyIfMissing
            );
            if (result.attached()) {
                st.imageUpdated = true;
                st.messages.add("Image updated");
            } else if (blankToNull(result.warning()) != null) {
                st.messages.add(result.warning());
            } else {
                st.messages.add(st.pendingImageOnlyIfMissing ? "Cover already present" : "Image unchanged");
            }
        }
    }

    private static RefreshCatalogResponse assemble(List<LineState> lines) {
        List<RefreshCatalogLineResponse> responses = new ArrayList<>(lines.size());
        int updated = 0;
        int skipped = 0;
        for (LineState st : lines) {
            boolean anyUpdate = st.sellUpdated || st.buyUpdated || st.imageUpdated;
            if (anyUpdate) {
                updated++;
            } else {
                skipped++;
            }
            responses.add(new RefreshCatalogLineResponse(
                    st.globalProductId,
                    st.itemId,
                    anyUpdate ? (st.dryRun ? "would_update" : "updated") : "skipped",
                    String.join("; ", st.messages),
                    st.currentSell,
                    st.recommendedSell,
                    st.currentBuy,
                    st.recommendedBuy,
                    st.sellUpdated,
                    st.buyUpdated,
                    st.imageUpdated
            ));
        }
        return new RefreshCatalogResponse(updated, skipped, responses);
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Mutable per-line accumulator. Price/image flags are filled in the transactional phase; the
     * image flag and message are completed in the post-transaction phase.
     */
    private static final class LineState {
        private final String globalProductId;
        private final String itemId;
        private final boolean dryRun;
        private final List<String> messages = new ArrayList<>();
        private BigDecimal currentSell;
        private BigDecimal recommendedSell;
        private BigDecimal currentBuy;
        private BigDecimal recommendedBuy;
        private boolean sellUpdated;
        private boolean buyUpdated;
        private boolean imageUpdated;
        private String pendingImageUrl;
        private boolean pendingImageOnlyIfMissing;

        private LineState(String globalProductId, String itemId, boolean dryRun) {
            this.globalProductId = globalProductId;
            this.itemId = itemId;
            this.dryRun = dryRun;
        }

        private static LineState skipped(String globalProductId, boolean dryRun, String message) {
            LineState state = new LineState(globalProductId, null, dryRun);
            state.messages.add(message);
            return state;
        }
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
