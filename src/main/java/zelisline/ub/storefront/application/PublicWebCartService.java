package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.storefront.api.dto.PublicCartLineResponse;
import zelisline.ub.storefront.api.dto.PublicCartResponse;
import zelisline.ub.storefront.api.dto.PublicUpsertCartLineRequest;
import zelisline.ub.storefront.domain.WebCart;
import zelisline.ub.storefront.domain.WebCartLine;
import zelisline.ub.storefront.repository.WebCartLineRepository;
import zelisline.ub.storefront.repository.WebCartRepository;

@Service
public class PublicWebCartService {

    private static final int CART_TTL_DAYS = 7;
    private static final int MAX_DISTINCT_LINES = 50;
    private static final BigDecimal MAX_QTY = new BigDecimal("9999");
    private static final int MONEY_SCALE = 2;

    private final PublicStorefrontContextService storefrontContextService;
    private final WebCartRepository webCartRepository;
    private final WebCartLineRepository webCartLineRepository;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final ItemImageRepository itemImageRepository;
    private final StorefrontCatalogStockService storefrontCatalogStockService;

    public PublicWebCartService(
            PublicStorefrontContextService storefrontContextService,
            WebCartRepository webCartRepository,
            WebCartLineRepository webCartLineRepository,
            ItemRepository itemRepository,
            PricingService pricingService,
            ItemImageRepository itemImageRepository,
            StorefrontCatalogStockService storefrontCatalogStockService
    ) {
        this.storefrontContextService = storefrontContextService;
        this.webCartRepository = webCartRepository;
        this.webCartLineRepository = webCartLineRepository;
        this.itemRepository = itemRepository;
        this.pricingService = pricingService;
        this.itemImageRepository = itemImageRepository;
        this.storefrontCatalogStockService = storefrontCatalogStockService;
    }

    @Transactional
    public PublicCartResponse createCart(String slug) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        Instant now = Instant.now();
        WebCart c = new WebCart();
        c.setId(UUID.randomUUID().toString());
        c.setBusinessId(ctx.business().getId());
        c.setCatalogBranchId(ctx.catalogBranch().getId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setExpiresAt(now.plus(CART_TTL_DAYS, ChronoUnit.DAYS));
        webCartRepository.save(c);
        return buildResponse(ctx, c, List.of());
    }

    @Transactional(readOnly = true)
    public PublicCartResponse getCart(String slug, String cartId) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        WebCart cart = loadActiveCart(ctx, cartId);
        List<WebCartLine> lines = webCartLineRepository.findByCartIdOrderByCreatedAtAsc(cartId);
        return buildResponse(ctx, cart, lines);
    }

    @Transactional
    public PublicCartResponse upsertLine(String slug, String cartId, PublicUpsertCartLineRequest req) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        WebCart cart = loadActiveCart(ctx, cartId);
        String itemId = req.itemId().trim();
        BigDecimal q = normalizeQty(req.quantity());
        if (q.signum() <= 0) {
            webCartLineRepository.deleteByCartIdAndItemId(cartId, itemId);
            touchCart(cart);
            return buildResponse(ctx, cart, webCartLineRepository.findByCartIdOrderByCreatedAtAsc(cartId));
        }
        requireWebItem(ctx, itemId);
        StorefrontOnlinePurchaseRules.requireWholeUnitQuantity(q);
        BigDecimal available = availableQtyAtCatalogBranch(ctx, itemId);
        if (available.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This item is out of stock at this store");
        }
        if (q.compareTo(available) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + available.stripTrailingZeros().toPlainString() + " available at this store");
        }
        requireCheckoutPrice(ctx, itemId);
        Optional<WebCartLine> row = webCartLineRepository.findByCartIdAndItemId(cartId, itemId);
        if (row.isEmpty() && webCartLineRepository.countByCartId(cartId) >= MAX_DISTINCT_LINES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is full");
        }
        WebCartLine line = row.orElseGet(() -> newWebLine(cartId, itemId));
        line.setQuantity(q);
        webCartLineRepository.save(line);
        touchCart(cart);
        return buildResponse(ctx, cart, webCartLineRepository.findByCartIdOrderByCreatedAtAsc(cartId));
    }

    @Transactional
    public PublicCartResponse removeLine(String slug, String cartId, String itemId) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        WebCart cart = loadActiveCart(ctx, cartId);
        webCartLineRepository.deleteByCartIdAndItemId(cartId, itemId.trim());
        touchCart(cart);
        return buildResponse(ctx, cart, webCartLineRepository.findByCartIdOrderByCreatedAtAsc(cartId));
    }

    private static WebCartLine newWebLine(String cartId, String itemId) {
        WebCartLine line = new WebCartLine();
        line.setCartId(cartId);
        line.setItemId(itemId);
        return line;
    }

    /** Validates slug + cart belong together and the cart has not expired. */
    @Transactional(readOnly = true)
    public WebCart requireActiveCart(PublicStorefrontContext ctx, String cartId) {
        return loadActiveCart(ctx, cartId);
    }

    private WebCart loadActiveCart(PublicStorefrontContext ctx, String cartId) {
        WebCart cart = webCartRepository.findByIdAndBusinessId(cartId, ctx.business().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!cart.getCatalogBranchId().equals(ctx.catalogBranch().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        if (Instant.now().isAfter(cart.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return cart;
    }

    private void touchCart(WebCart cart) {
        Instant n = Instant.now();
        cart.setUpdatedAt(n);
        cart.setExpiresAt(n.plus(CART_TTL_DAYS, ChronoUnit.DAYS));
        webCartRepository.save(cart);
    }

    private void requireWebItem(PublicStorefrontContext ctx, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, ctx.business().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item"));
        if (!item.isWebPublished() || !item.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not available");
        }
        StorefrontOnlinePurchaseRules.requireWebCartEligible(item);
    }

    private BigDecimal availableQtyAtCatalogBranch(PublicStorefrontContext ctx, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, ctx.business().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        return storefrontCatalogStockService.displayQtyForItem(
                ctx.business().getId(), ctx.catalogBranch().getId(), item);
    }

    private static BigDecimal normalizeQty(BigDecimal q) {
        if (q == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity required");
        }
        BigDecimal n = q.setScale(4, RoundingMode.HALF_UP);
        if (n.compareTo(MAX_QTY) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity too large");
        }
        return n;
    }

    /**
     * Validates cart contents for checkout: non-empty, every line sellable on web with branch sell price.
     */
    @Transactional(readOnly = true)
    public CheckoutEligibility requireCheckoutEligible(String slug, String cartId) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        WebCart cart = loadActiveCart(ctx, cartId);
        List<WebCartLine> raw = webCartLineRepository.findByCartIdOrderByCreatedAtAsc(cartId);
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is empty");
        }
        List<String> itemIds = raw.stream().map(WebCartLine::getItemId).toList();
        String businessId = ctx.business().getId();
        Map<String, Item> itemsById = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<String, BigDecimal> prices = loadPrices(businessId, ctx.catalogBranch().getId(), itemIds);
        BigDecimal grandTotal = BigDecimal.ZERO;
        List<CheckoutLine> out = new ArrayList<>();
        List<String> unpricedLabels = new ArrayList<>();
        int idx = 0;
        for (WebCartLine row : raw) {
            Item it = itemsById.get(row.getItemId());
            if (it == null || !it.isWebPublished() || !it.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item no longer available");
            }
            StorefrontOnlinePurchaseRules.requireWebCartEligible(it);
            StorefrontOnlinePurchaseRules.requireWholeUnitQuantity(row.getQuantity());
            BigDecimal avail = availableQtyAtCatalogBranch(ctx, row.getItemId());
            if (row.getQuantity().compareTo(avail) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Insufficient stock for one or more items (only "
                                + avail.stripTrailingZeros().toPlainString()
                                + " left at this store for an item in your cart)");
            }
            BigDecimal unit = prices.get(row.getItemId());
            if (unit == null) {
                unpricedLabels.add(checkoutItemLabel(it));
                continue;
            }
            BigDecimal lineTotal = unit.multiply(row.getQuantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            grandTotal = grandTotal.add(lineTotal);
            out.add(new CheckoutLine(it, row.getQuantity(), unit, lineTotal, idx++));
        }
        if (!unpricedLabels.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Pricing unavailable for checkout: "
                            + String.join(", ", unpricedLabels)
                            + ". Set a shelf price in Products or remove these items from your cart.");
        }
        return new CheckoutEligibility(cart, ctx, out, grandTotal);
    }

    private void requireCheckoutPrice(PublicStorefrontContext ctx, String itemId) {
        BigDecimal unit = pricingService.getCurrentOpenSellingPrice(
                ctx.business().getId(), itemId, ctx.catalogBranch().getId());
        if (unit == null) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, ctx.business().getId())
                    .orElse(null);
            String label = item != null ? checkoutItemLabel(item) : itemId;
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No online price for " + label + ". Set a shelf price in Products before adding to cart.");
        }
    }

    private static String checkoutItemLabel(Item it) {
        String sku = it.getSku() != null ? it.getSku().trim() : "";
        String name = it.getName() != null ? it.getName().trim() : "item";
        if (!sku.isEmpty()) {
            return sku + " (" + name + ")";
        }
        return name;
    }

    public record CheckoutLine(
            Item item,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            int lineIndex
    ) {}

    public record CheckoutEligibility(
            WebCart cart,
            PublicStorefrontContext ctx,
            List<CheckoutLine> lines,
            BigDecimal grandTotal
    ) {}

    private PublicCartResponse buildResponse(
            PublicStorefrontContext ctx,
            WebCart cart,
            List<WebCartLine> lines
    ) {
        if (lines.isEmpty()) {
            return new PublicCartResponse(
                    cart.getId(),
                    ctx.business().getCurrency(),
                    cart.getCatalogBranchId(),
                    ctx.catalogBranch().getName(),
                    cart.getExpiresAt(),
                    null,
                    List.of()
            );
        }
        List<String> itemIds = lines.stream().map(WebCartLine::getItemId).toList();
        String businessId = ctx.business().getId();
        Map<String, Item> itemsById = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<String, BigDecimal> prices = loadPrices(businessId, ctx.catalogBranch().getId(), itemIds);
        Map<String, String> thumbs = firstGalleryUrlByItemIds(itemIds);
        List<PublicCartLineResponse> out = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        boolean allPriced = true;
        for (WebCartLine L : lines) {
            Item it = itemsById.get(L.getItemId());
            if (it == null || !it.isWebPublished() || !it.isActive()) {
                continue;
            }
            BigDecimal unit = prices.get(L.getItemId());
            BigDecimal lineTotal = null;
            if (unit != null) {
                lineTotal = unit.multiply(L.getQuantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                subtotal = subtotal.add(lineTotal);
            } else {
                allPriced = false;
            }
            out.add(new PublicCartLineResponse(
                    L.getItemId(),
                    it.getSku(),
                    it.getName(),
                    blankToNull(it.getVariantName()),
                    thumbs.get(L.getItemId()),
                    L.getQuantity(),
                    unit,
                    lineTotal
            ));
        }
        return new PublicCartResponse(
                cart.getId(),
                ctx.business().getCurrency(),
                cart.getCatalogBranchId(),
                ctx.catalogBranch().getName(),
                cart.getExpiresAt(),
                allPriced && !out.isEmpty() ? subtotal : null,
                out
        );
    }

    private Map<String, BigDecimal> loadPrices(String businessId, String branchId, List<String> itemIds) {
        return pricingService.getCurrentOpenSellingPricesForItems(businessId, branchId, itemIds);
    }

    private Map<String, String> firstGalleryUrlByItemIds(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Sort galleryOrder = Sort.by(
                Sort.Order.asc("itemId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        List<ItemImage> rows = itemImageRepository.findByItemIdIn(itemIds, galleryOrder);
        Map<String, String> out = new LinkedHashMap<>();
        for (ItemImage img : rows) {
            String url = resolveImagePublicUrl(img);
            if (url == null) {
                continue;
            }
            out.putIfAbsent(img.getItemId(), url);
        }
        return out;
    }

    private static String resolveImagePublicUrl(ItemImage img) {
        String secure = img.getSecureUrl();
        if (secure != null && !secure.isBlank()) {
            return secure.trim();
        }
        String key = img.getS3Key();
        if (key != null) {
            String k = key.trim();
            if (k.startsWith("http://") || k.startsWith("https://")) {
                return k;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
