package zelisline.ub.marketplace.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.BuyerSessionResponse;
import zelisline.ub.marketplace.api.dto.OpenBuyerSessionRequest;
import zelisline.ub.marketplace.application.MarketplaceBuyerSessionService;

/**
 * Sign-in for a buyer on the marketplace. The code is sent and checked by the platform's existing
 * door ({@code /api/v1/public/shopper/auth/identify/...}); this turns the token that door returns
 * into a session.
 */
@RestController
@RequestMapping("/api/v1/public/marketplace/buyer")
@RequiredArgsConstructor
public class MarketplaceBuyerSessionController {

    /** The header a signed-in buyer sends. It is not a shop sign-in and opens no shop's pages. */
    public static final String BUYER_TOKEN_HEADER = "X-Marketplace-Buyer";

    private final MarketplaceBuyerSessionService buyerSessionService;

    @PostMapping("/session")
    public ResponseEntity<BuyerSessionResponse> open(@Valid @RequestBody OpenBuyerSessionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(buyerSessionService.open(body.phone(), body.verificationToken()));
    }

    @GetMapping("/session")
    public ResponseEntity<BuyerSessionResponse> current(
            @RequestHeader(value = BUYER_TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(buyerSessionService.current(token));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> close(
            @RequestHeader(value = BUYER_TOKEN_HEADER, required = false) String token) {
        buyerSessionService.close(token);
        return ResponseEntity.noContent().build();
    }
}
