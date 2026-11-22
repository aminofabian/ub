package zelisline.ub.marketplace.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.StartStandingOrderRequest;
import zelisline.ub.marketplace.api.dto.SubmitBasketRequest;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.wholesale.api.dto.StandingOrderResponse;
import zelisline.ub.wholesale.application.StandingOrderService;

/**
 * A signed-in marketplace buyer's standing orders (P4): start one with a supplier, see them, stop
 * one. Every call checks the buyer is staff of the shop it names (ADR 0043).
 */
@Service
@RequiredArgsConstructor
public class MarketplaceStandingOrderService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final MarketplaceBuyerSessionService buyerSessionService;
    private final MarketplaceSupplierRepository supplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final StandingOrderService standingOrderService;

    public StandingOrderResponse start(String buyerToken, StartStandingOrderRequest req) {
        MarketplaceBuyerSessionService.Buyer buyer =
                buyerSessionService.requireBuyerFor(buyerToken, req.buyerBusinessId().trim());
        String supplierId = req.supplierId().trim();
        MarketplaceSupplier passport = supplierRepository.findById(supplierId)
                .filter(p -> MarketplaceSupplierStatuses.ACTIVE.equals(p.getStatus()) && p.isListed())
                .filter(p -> p.getBusinessId() != null && !p.getBusinessId().isBlank())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This supplier has no shop to order from yet"));
        if (passport.getBusinessId().equals(buyer.businessId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot order from your own shop");
        }
        List<StandingOrderService.Wanted> wanted = new ArrayList<>();
        for (SubmitBasketRequest.Line line : req.lines()) {
            MarketplaceSupplierProduct product = productRepository.findById(line.productId().trim())
                    .filter(p -> supplierId.equals(p.getMarketplaceSupplierId()))
                    .filter(p -> p.getItemId() != null && !p.getItemId().isBlank())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "One of these products cannot be ordered from the shop yet"));
            wanted.add(new StandingOrderService.Wanted(product.getItemId(), line.quantity()));
        }
        return standingOrderService.start(
                passport.getBusinessId(),
                buyer.businessId(),
                buyer.businessName(),
                req.buyerName().trim(),
                buyer.phone(),
                req.everyDays(),
                wanted,
                LocalDate.now(NAIROBI));
    }

    public List<StandingOrderResponse> mine(String buyerToken, String buyerBusinessId) {
        return standingOrderService.forBuyer(buyerSessionService.requireBuyerFor(buyerToken, buyerBusinessId).businessId());
    }

    public void stop(String buyerToken, String buyerBusinessId, String standingOrderId) {
        standingOrderService.stopAsBuyer(
                buyerSessionService.requireBuyerFor(buyerToken, buyerBusinessId).businessId(), standingOrderId);
    }
}
