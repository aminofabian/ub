package zelisline.ub.restaurant.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOrderConfirmation;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicOrderRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicTableMenu;
import zelisline.ub.restaurant.application.PublicTableOrderService;

/**
 * What a customer reaches by scanning the QR code on a table. Nobody is signed in: the business
 * comes from the slug in the path, never from a header, and {@link PublicTableOrderService}
 * checks and limits everything.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/table-orders/{token}")
@RequiredArgsConstructor
public class PublicTableOrderController {

    private final PublicTableOrderService service;

    @GetMapping
    public ResponseEntity<PublicTableMenu> menu(
            @PathVariable String slug, @PathVariable String token, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.menu(slug, token, address(request)));
    }

    @PostMapping
    public ResponseEntity<PublicOrderConfirmation> order(
            @PathVariable String slug, @PathVariable String token, @Valid @RequestBody PublicOrderRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.order(slug, token, body, address(request)));
    }

    /**
     * The caller's address as the other public rate limits read it. The header can be forged, so
     * this only slows a casual flood; what bounds the damage is that a customer's lines are never
     * sent to the kitchen without a member of staff, and an order holds only so many of them.
     */
    private static String address(HttpServletRequest request) {
        String address = zelisline.ub.platform.security.ClientIpResolver.resolve(request);
        return address != null && address.length() > 64 ? address.substring(0, 64) : address;
    }
}
