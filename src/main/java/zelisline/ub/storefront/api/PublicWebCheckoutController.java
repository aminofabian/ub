package zelisline.ub.storefront.api;

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
import zelisline.ub.storefront.api.dto.PublicCheckoutRequest;
import zelisline.ub.storefront.api.dto.PublicCheckoutResponse;
import zelisline.ub.storefront.application.PublicWebCheckoutService;

@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/carts/{cartId}")
@RequiredArgsConstructor
public class PublicWebCheckoutController {

    private final PublicWebCheckoutService publicWebCheckoutService;

    @PostMapping("/checkout")
    public ResponseEntity<PublicCheckoutResponse> checkout(
            @PathVariable String slug,
            @PathVariable String cartId,
            @Valid @RequestBody PublicCheckoutRequest body
    ) {
        PublicCheckoutResponse out = publicWebCheckoutService.submitCheckout(slug, cartId, body);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(out);
    }
}
