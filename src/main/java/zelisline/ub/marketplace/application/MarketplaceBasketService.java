package zelisline.ub.marketplace.application;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.marketplace.api.dto.SubmitBasketRequest;
import zelisline.ub.marketplace.api.dto.SubmitBasketResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.storefront.api.dto.PublicUpsertCartLineRequest;
import zelisline.ub.storefront.application.PublicWebCartService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.wholesale.api.dto.PlaceSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.SalesOrderPlacedResponse;
import zelisline.ub.wholesale.application.SalesOrderService;

/**
 * One basket filled from several suppliers on the marketplace (ADR 0043, P3-3).
 *
 * <p>Each supplier's lines become one trade order in that supplier's own shop, placed through the
 * shop's own cart so every rule the shop has still applies: stock, prices, quantity prices, the
 * minimum order. The buyer is a signed-in marketplace buyer ordering for a shop they are staff of,
 * so the order names that shop and nobody has to vouch for it later.
 *
 * <p>Each supplier is placed in its own transaction. One that cannot be placed is reported and the
 * rest still go through.
 */
@Service
public class MarketplaceBasketService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceBasketService.class);

    private final MarketplaceBuyerSessionService buyerSessionService;
    private final MarketplaceSupplierRepository supplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final PublicWebCartService cartService;
    private final SalesOrderService salesOrderService;
    private final TransactionTemplate perSupplier;

    public MarketplaceBasketService(
            MarketplaceBuyerSessionService buyerSessionService,
            MarketplaceSupplierRepository supplierRepository,
            MarketplaceSupplierProductRepository productRepository,
            BusinessRepository businessRepository,
            PublicWebCartService cartService,
            SalesOrderService salesOrderService,
            PlatformTransactionManager transactionManager
    ) {
        this.buyerSessionService = buyerSessionService;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.businessRepository = businessRepository;
        this.cartService = cartService;
        this.salesOrderService = salesOrderService;
        this.perSupplier = new TransactionTemplate(transactionManager);
        this.perSupplier.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Not transactional itself: every supplier's order commits or fails on its own. */
    public SubmitBasketResponse submit(String buyerToken, SubmitBasketRequest req) {
        MarketplaceBuyerSessionService.Buyer buyer =
                buyerSessionService.requireBuyerFor(buyerToken, req.buyerBusinessId().trim());
        List<SubmitBasketResponse.Result> results = new ArrayList<>();
        for (SubmitBasketRequest.Supplier part : req.suppliers()) {
            String supplierId = part.supplierId().trim();
            try {
                results.add(perSupplier.execute(status -> placeOne(buyer, req, part)));
            } catch (ResponseStatusException refused) {
                results.add(notPlaced(supplierId, nameOf(supplierId), refused.getReason()));
            } catch (RuntimeException failed) {
                log.warn("marketplace basket: supplier={} could not be placed: {}", supplierId, failed.getMessage());
                results.add(notPlaced(supplierId, nameOf(supplierId), "This order could not be placed. Try again."));
            }
        }
        return new SubmitBasketResponse(results);
    }

    private SubmitBasketResponse.Result placeOne(
            MarketplaceBuyerSessionService.Buyer buyer,
            SubmitBasketRequest req,
            SubmitBasketRequest.Supplier part
    ) {
        String supplierId = part.supplierId().trim();
        MarketplaceSupplier passport = supplierRepository.findById(supplierId)
                .filter(p -> MarketplaceSupplierStatuses.ACTIVE.equals(p.getStatus()) && p.isListed())
                .orElse(null);
        if (passport == null) {
            return notPlaced(supplierId, null, "This supplier is no longer listed.");
        }
        Business shop = passport.getBusinessId() == null
                ? null
                : businessRepository.findById(passport.getBusinessId()).orElse(null);
        if (shop == null || shop.getSlug() == null || shop.getSlug().isBlank()) {
            return notPlaced(supplierId, passport.getName(),
                    "This supplier has no shop to order from yet. Contact them directly.");
        }
        if (shop.getId().equals(buyer.businessId())) {
            return notPlaced(supplierId, passport.getName(), "You cannot order from your own shop.");
        }

        String slug = shop.getSlug();
        String cartId = cartService.createCart(slug).id();
        List<String> leftOut = new ArrayList<>();
        int added = 0;
        for (SubmitBasketRequest.Line line : part.lines()) {
            // Only this supplier's own product, and only one that is in its shop.
            MarketplaceSupplierProduct product = productRepository.findById(line.productId().trim())
                    .filter(p -> supplierId.equals(p.getMarketplaceSupplierId()))
                    .orElse(null);
            if (product == null) {
                leftOut.add("a product that is no longer listed");
                continue;
            }
            if (product.getItemId() == null || product.getItemId().isBlank()) {
                leftOut.add(product.getName());
                continue;
            }
            cartService.upsertLine(slug, cartId, new PublicUpsertCartLineRequest(product.getItemId(), line.quantity()));
            added++;
        }
        if (added == 0) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "None of these products can be ordered from the shop yet.");
        }
        SalesOrderPlacedResponse placed = salesOrderService.placeFromCart(
                slug,
                cartId,
                new PlaceSalesOrderRequest(req.buyerName().trim(), buyer.phone(), null, buyer.businessName(), req.notes()),
                buyer.businessId());
        return new SubmitBasketResponse.Result(
                supplierId,
                passport.getName(),
                true,
                placed.orderId(),
                placed.orderCode(),
                placed.total(),
                placed.currency(),
                slug,
                leftOut.isEmpty() ? null : "Left out, not in the shop yet: " + String.join(", ", leftOut));
    }

    private String nameOf(String supplierId) {
        return supplierRepository.findById(supplierId).map(MarketplaceSupplier::getName).orElse(null);
    }

    private static SubmitBasketResponse.Result notPlaced(String supplierId, String supplierName, String why) {
        return new SubmitBasketResponse.Result(supplierId, supplierName, false, null, null, null, null, null, why);
    }
}
