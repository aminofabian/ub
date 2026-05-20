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
import zelisline.ub.storefront.api.dto.PublicWebStkPushRequest;
import zelisline.ub.storefront.api.dto.PublicWebStkPushResponse;
import zelisline.ub.storefront.application.PublicStorefrontPaymentService;

@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/orders")
@RequiredArgsConstructor
public class PublicWebOrderPaymentController {

    private final PublicStorefrontPaymentService publicStorefrontPaymentService;

    @PostMapping("/{orderId}/stk-push")
    public ResponseEntity<PublicWebStkPushResponse> stkPush(
            @PathVariable String slug,
            @PathVariable String orderId,
            @Valid @RequestBody PublicWebStkPushRequest body
    ) {
        PublicWebStkPushResponse out = publicStorefrontPaymentService.initiateOrderStkPush(
                slug,
                orderId,
                body.configId(),
                body.phoneNumber()
        );
        return ResponseEntity.status(HttpStatus.OK).cacheControl(CacheControl.noStore()).body(out);
    }
}
