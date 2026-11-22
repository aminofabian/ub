package zelisline.ub.marketplace.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SubmitBasketRequest;
import zelisline.ub.marketplace.api.dto.SubmitBasketResponse;
import zelisline.ub.marketplace.application.MarketplaceBasketService;

/** A signed-in marketplace buyer sends one basket; each supplier in it gets its own order. */
@RestController
@RequestMapping("/api/v1/public/marketplace/buyer")
@RequiredArgsConstructor
public class MarketplaceBasketController {

    private final MarketplaceBasketService basketService;

    @PostMapping("/basket")
    public ResponseEntity<SubmitBasketResponse> submit(
            @RequestHeader(value = MarketplaceBuyerSessionController.BUYER_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody SubmitBasketRequest body
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(basketService.submit(token, body));
    }
}
