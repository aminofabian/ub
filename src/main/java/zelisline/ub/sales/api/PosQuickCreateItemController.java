package zelisline.ub.sales.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.sales.api.dto.PosQuickCreateItemRequest;
import zelisline.ub.sales.application.PosQuickCreateItemService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/pos")
@RequiredArgsConstructor
public class PosQuickCreateItemController {

    private final PosQuickCreateItemService posQuickCreateItemService;

    @PostMapping("/quick-items")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<ItemResponse> create(
            @Valid @RequestBody PosQuickCreateItemRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        ItemResponse created = posQuickCreateItemService.create(
                TenantRequestIds.resolveBusinessId(request),
                principal.roleId(),
                principal.userId(),
                body,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
