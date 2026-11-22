package zelisline.ub.wholesale.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.wholesale.api.dto.PlaceSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.SalesOrderPlacedResponse;
import zelisline.ub.wholesale.application.SalesOrderService;

/** A buyer on a supplier's storefront sends the cart as a trade order. No payment, no stock taken. */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/carts/{cartId}")
@RequiredArgsConstructor
public class PublicSalesOrderController {

    private final SalesOrderService salesOrderService;

    @PostMapping("/trade-order")
    public ResponseEntity<SalesOrderPlacedResponse> place(
            @PathVariable String slug,
            @PathVariable String cartId,
            @Valid @RequestBody PlaceSalesOrderRequest body
    ) {
        SalesOrderPlacedResponse out = salesOrderService.placeFromCart(slug, cartId, body);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(out);
    }
}
