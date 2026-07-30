package zelisline.ub.platform.api;

import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.DomainOrderResponse;
import zelisline.ub.tenancy.application.DomainPurchaseService;

/**
 * Super Admin ops queue for Kenyan TLD purchases: mark paid, attach HA id, sync, mark NS.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/domain-orders")
@RequiredArgsConstructor
public class SuperAdminDomainOrdersController {

    private final DomainPurchaseService domainPurchaseService;

    @GetMapping
    public List<DomainOrderResponse> list(@RequestParam(required = false) String status) {
        return domainPurchaseService.listAllOrders(status);
    }

    @GetMapping("/{orderId}")
    public DomainOrderResponse get(@PathVariable String orderId) {
        return domainPurchaseService.getOrderForSuperAdmin(orderId);
    }

    @PostMapping("/{orderId}/mark-paid")
    public DomainOrderResponse markPaid(@PathVariable String orderId) {
        return domainPurchaseService.markPaid(orderId);
    }

    @PostMapping("/{orderId}/mark-ns-active")
    public DomainOrderResponse markNsActive(@PathVariable String orderId) {
        return domainPurchaseService.markNsActive(orderId);
    }

    @PostMapping("/{orderId}/sync")
    public DomainOrderResponse sync(@PathVariable String orderId) {
        return domainPurchaseService.syncOrderForSuperAdmin(orderId);
    }

    @PostMapping("/sync-open")
    public Map<String, Integer> syncOpen() {
        return Map.of("advanced", domainPurchaseService.syncOpenOrders());
    }

    @PostMapping("/{orderId}/refresh-register-url")
    public DomainOrderResponse refreshRegisterUrl(@PathVariable String orderId) {
        return domainPurchaseService.refreshRegisterUrlForSuperAdmin(orderId);
    }

    @PostMapping("/{orderId}/attach-hostafrica")
    public DomainOrderResponse attachHostafrica(
            @PathVariable String orderId,
            @RequestBody AttachHostafricaRequest body
    ) {
        return domainPurchaseService.attachHostafricaId(
                orderId,
                body == null ? null : body.hostafricaDomainId()
        );
    }

    public record AttachHostafricaRequest(@NotBlank String hostafricaDomainId) {}
}
