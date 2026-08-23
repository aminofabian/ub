package zelisline.ub.storefront.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.PublicOrderTrackingResponse;
import zelisline.ub.storefront.application.PublicWebOrderTrackingService;

/**
 * Public guest tracking by short order code (scope §15): the link quoted in the
 * WhatsApp order message. Access is gated by code + phone last-4.
 */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/orders/by-code")
@RequiredArgsConstructor
public class PublicWebOrderTrackingController {

    private final PublicWebOrderTrackingService trackingService;

    @GetMapping("/{code}")
    public ResponseEntity<PublicOrderTrackingResponse> track(
            @PathVariable String slug,
            @PathVariable String code,
            @RequestParam(name = "phoneLast4", required = false) String phoneLast4
    ) {
        PublicOrderTrackingResponse body =
                trackingService.trackByCode(slug, code, phoneLast4);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
