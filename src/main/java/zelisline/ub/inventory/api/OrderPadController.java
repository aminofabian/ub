package zelisline.ub.inventory.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.api.dto.OrderPadDtos.CreateOrderPadBatchRequest;
import zelisline.ub.inventory.api.dto.OrderPadDtos.CreateOrderPadItemRequest;
import zelisline.ub.inventory.api.dto.OrderPadDtos.OrderPadItemResponse;
import zelisline.ub.inventory.api.dto.OrderPadDtos.SetOrderedRequest;
import zelisline.ub.inventory.application.OrderPadService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/order-pad/items")
@RequiredArgsConstructor
public class OrderPadController {

    private final OrderPadService orderPadService;
    private final RequestPermissionService requestPermissionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'order_pad.read')")
    public List<OrderPadItemResponse> list(
            @RequestParam String branchId,
            @RequestParam(required = false) Boolean ordered,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return orderPadService.list(
                TenantRequestIds.resolveBusinessId(request),
                principal.roleId(),
                principal.branchId(),
                branchId,
                ordered
        );
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'order_pad.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderPadItemResponse create(
            @Valid @RequestBody CreateOrderPadItemRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return orderPadService.create(
                TenantRequestIds.resolveBusinessId(request),
                principal.roleId(),
                principal.branchId(),
                principal.userId(),
                body
        );
    }

    @PostMapping("/batch")
    @PreAuthorize("hasPermission(null, 'order_pad.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public List<OrderPadItemResponse> createBatch(
            @Valid @RequestBody CreateOrderPadBatchRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return orderPadService.createBatch(
                TenantRequestIds.resolveBusinessId(request),
                principal.roleId(),
                principal.branchId(),
                principal.userId(),
                body
        );
    }

    @PostMapping("/{itemId}/ordered")
    @PreAuthorize("hasPermission(null, 'order_pad.manage')")
    public OrderPadItemResponse setOrdered(
            @PathVariable String itemId,
            @Valid @RequestBody SetOrderedRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return orderPadService.setOrdered(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                itemId,
                body.ordered()
        );
    }

    @DeleteMapping("/{itemId}")
    @PreAuthorize("hasPermission(null, 'order_pad.write') or hasPermission(null, 'order_pad.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String itemId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        boolean canManage = requestPermissionService.hasPermission(
                principal.roleId(), "order_pad.manage");
        orderPadService.delete(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                canManage,
                itemId
        );
    }
}
