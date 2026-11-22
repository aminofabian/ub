package zelisline.ub.wholesale.api;

import java.util.Map;

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
import zelisline.ub.wholesale.api.dto.ApplyTradeAccountRequest;
import zelisline.ub.wholesale.application.TradeAccountService;

/** A buyer asks a supplier's shop for an account. The reply never says what was decided. */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/trade-accounts")
@RequiredArgsConstructor
public class PublicTradeAccountController {

    private final TradeAccountService tradeAccountService;

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> apply(
            @PathVariable String slug,
            @Valid @RequestBody ApplyTradeAccountRequest body
    ) {
        tradeAccountService.apply(slug, body);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("received", true));
    }
}
