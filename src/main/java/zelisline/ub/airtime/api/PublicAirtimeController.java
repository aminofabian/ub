package zelisline.ub.airtime.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.PublicAirtimeConfigResponse;
import zelisline.ub.airtime.api.dto.PublicAirtimeOrderRequest;
import zelisline.ub.airtime.api.dto.PublicAirtimeOrderResponse;
import zelisline.ub.airtime.application.PublicAirtimeService;

/** Storefront airtime for shoppers, resolved by store slug. */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/airtime")
@RequiredArgsConstructor
public class PublicAirtimeController {

    private final PublicAirtimeService publicAirtimeService;

    @GetMapping
    public ResponseEntity<PublicAirtimeConfigResponse> config(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(publicAirtimeService.config(slug));
    }

    @PostMapping("/orders")
    public ResponseEntity<PublicAirtimeOrderResponse> createOrder(
            @PathVariable String slug,
            @Valid @RequestBody PublicAirtimeOrderRequest body
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(publicAirtimeService.createOrder(slug, body));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<PublicAirtimeOrderResponse> status(
            @PathVariable String slug,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(publicAirtimeService.status(slug, orderId));
    }
}
