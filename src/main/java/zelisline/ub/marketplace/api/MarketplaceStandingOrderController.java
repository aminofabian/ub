package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.StartStandingOrderRequest;
import zelisline.ub.marketplace.application.MarketplaceStandingOrderService;
import zelisline.ub.wholesale.api.dto.StandingOrderResponse;

/** A signed-in marketplace buyer's standing orders. */
@RestController
@RequestMapping("/api/v1/public/marketplace/buyer/standing-orders")
@RequiredArgsConstructor
public class MarketplaceStandingOrderController {

    private static final String BUYER = MarketplaceBuyerSessionController.BUYER_TOKEN_HEADER;

    private final MarketplaceStandingOrderService standingOrders;

    @PostMapping
    public ResponseEntity<StandingOrderResponse> start(
            @RequestHeader(value = BUYER, required = false) String token,
            @Valid @RequestBody StartStandingOrderRequest body
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(standingOrders.start(token, body));
    }

    @GetMapping
    public List<StandingOrderResponse> mine(
            @RequestHeader(value = BUYER, required = false) String token,
            @RequestParam String buyerBusinessId
    ) {
        return standingOrders.mine(token, buyerBusinessId);
    }

    @DeleteMapping("/{standingOrderId}")
    public ResponseEntity<Void> stop(
            @RequestHeader(value = BUYER, required = false) String token,
            @RequestParam String buyerBusinessId,
            @PathVariable String standingOrderId
    ) {
        standingOrders.stop(token, buyerBusinessId, standingOrderId);
        return ResponseEntity.noContent().build();
    }
}
