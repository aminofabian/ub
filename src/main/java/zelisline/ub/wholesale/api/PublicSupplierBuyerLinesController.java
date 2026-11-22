package zelisline.ub.wholesale.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.wholesale.api.dto.BuyerLinesResponse;
import zelisline.ub.wholesale.application.SupplierStandingService;

/** Public trust lines for a listed supplier, by its marketplace id. Empty and off unless the flag is on. */
@RestController
@RequestMapping("/api/v1/public/marketplace/suppliers")
@RequiredArgsConstructor
public class PublicSupplierBuyerLinesController {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierStandingService supplierStandingService;

    @GetMapping("/{id}/buyer-lines")
    public BuyerLinesResponse buyerLines(@PathVariable String id) {
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(id)
                .filter(MarketplaceSupplier::isListed)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        return supplierStandingService.buyerLines(passport.getBusinessId());
    }
}
