package zelisline.ub.sales.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.sales.api.dto.PosSetItemWeighedRequest;
import zelisline.ub.sales.application.PosSetItemWeighedService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/pos")
@RequiredArgsConstructor
public class PosSetItemWeighedController {

    private final PosSetItemWeighedService posSetItemWeighedService;

    @PutMapping("/items/{itemId}/weighed")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ItemResponse setWeighed(
            @PathVariable String itemId,
            @Valid @RequestBody PosSetItemWeighedRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return posSetItemWeighedService.setWeighed(
                TenantRequestIds.resolveBusinessId(request),
                principal.roleId(),
                principal.userId(),
                itemId,
                body
        );
    }
}
