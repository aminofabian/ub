package zelisline.ub.storefront.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.PatchCheckoutContactRequest;
import zelisline.ub.storefront.api.dto.PatchCheckoutDeliveryRequest;
import zelisline.ub.storefront.api.dto.PublicCheckoutStateResponse;
import zelisline.ub.storefront.application.ShopperCheckoutStateService;

@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/carts/{cartId}/checkout-state")
@RequiredArgsConstructor
public class PublicWebCheckoutStateController {

    private final ShopperCheckoutStateService shopperCheckoutStateService;

    @GetMapping
    public ResponseEntity<PublicCheckoutStateResponse> getState(
            @PathVariable String slug,
            @PathVariable String cartId,
            HttpServletRequest request
    ) {
        PublicCheckoutStateResponse body = shopperCheckoutStateService.getState(slug, cartId, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PatchMapping("/contact")
    public ResponseEntity<PublicCheckoutStateResponse> saveContact(
            @PathVariable String slug,
            @PathVariable String cartId,
            HttpServletRequest request,
            @Valid @RequestBody PatchCheckoutContactRequest body
    ) {
        PublicCheckoutStateResponse out =
                shopperCheckoutStateService.saveContact(slug, cartId, request, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @PatchMapping("/delivery")
    public ResponseEntity<PublicCheckoutStateResponse> saveDelivery(
            @PathVariable String slug,
            @PathVariable String cartId,
            HttpServletRequest request,
            @Valid @RequestBody PatchCheckoutDeliveryRequest body
    ) {
        PublicCheckoutStateResponse out =
                shopperCheckoutStateService.saveDelivery(slug, cartId, request, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
