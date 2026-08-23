package zelisline.ub.storefront.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.application.PublicWebOrderHandoffService;

/**
 * Fire-and-forget "the shopper opened the chat" marker (scope §15, Phase 2).
 * The client sends it with {@code keepalive}; never blocks the redirect.
 */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/orders")
@RequiredArgsConstructor
public class PublicWebOrderHandoffController {

    private final PublicWebOrderHandoffService handoffService;

    @PostMapping("/{orderId}/whatsapp-handoff")
    public ResponseEntity<Void> recordOpened(
            @PathVariable String slug,
            @PathVariable String orderId
    ) {
        if (!handoffService.recordOpened(slug, orderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return ResponseEntity.noContent().build();
    }
}
