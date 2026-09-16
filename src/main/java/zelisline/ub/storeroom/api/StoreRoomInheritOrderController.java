package zelisline.ub.storeroom.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.storeroom.api.dto.InheritOrderApplyResponse;
import zelisline.ub.storeroom.api.dto.InheritOrderPreviewResponse;
import zelisline.ub.storeroom.api.dto.InheritOrderRequest;
import zelisline.ub.storeroom.application.StoreRoomInheritOrderService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Put a purchase order into the store room. Does not unpack / GRN — that stays
 * on Confirm order so stock is not counted twice.
 */
@Validated
@RestController
@RequestMapping("/api/v1/store-room/inherit-order")
@RequiredArgsConstructor
public class StoreRoomInheritOrderController {

    private final StoreRoomInheritOrderService inheritOrderService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public InheritOrderPreviewResponse preview(
            @RequestParam String purchaseOrderId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return inheritOrderService.preview(
                TenantRequestIds.resolveBusinessId(request), purchaseOrderId);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    @ResponseStatus(HttpStatus.CREATED)
    public InheritOrderApplyResponse apply(
            @Valid @RequestBody InheritOrderRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal actor = CurrentTenantUser.requireHuman(request);
        return inheritOrderService.apply(
                TenantRequestIds.resolveBusinessId(request),
                actor.userId(),
                actor.roleId(),
                actor.branchId(),
                body);
    }
}
