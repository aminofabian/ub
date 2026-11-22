package zelisline.ub.wholesale.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.wholesale.api.dto.SalesOrderTrackingResponse;
import zelisline.ub.wholesale.application.SalesOrderService;

/** A buyer checks the trade order they placed, by its id. */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/trade-orders")
@RequiredArgsConstructor
public class PublicSalesOrderTrackingController {

    private final SalesOrderService salesOrderService;

    @GetMapping("/{orderId}")
    public ResponseEntity<SalesOrderTrackingResponse> track(
            @PathVariable String slug,
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(salesOrderService.track(slug, orderId));
    }
}
