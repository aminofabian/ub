package zelisline.ub.suppliers.api;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.suppliers.api.dto.AddItemSupplierLinkRequest;
import zelisline.ub.suppliers.api.dto.ItemSupplierLinkResponse;
import zelisline.ub.suppliers.application.ItemSupplierLinkService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/items/{itemId}/supplier-links")
@RequiredArgsConstructor
public class ItemSupplierLinksController {

    private final ItemSupplierLinkService itemSupplierLinkService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<ItemSupplierLinkResponse> list(@PathVariable String itemId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return itemSupplierLinkService.listLinks(TenantRequestIds.resolveBusinessId(request), itemId);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.link_suppliers')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemSupplierLinkResponse add(
            @PathVariable String itemId,
            @Valid @RequestBody AddItemSupplierLinkRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemSupplierLinkService.addLink(TenantRequestIds.resolveBusinessId(request), itemId, body);
    }

    @DeleteMapping("/{linkId}")
    @PreAuthorize("hasPermission(null, 'catalog.items.link_suppliers')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable String itemId,
            @PathVariable String linkId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        itemSupplierLinkService.removeLink(TenantRequestIds.resolveBusinessId(request), itemId, linkId);
    }

    @PostMapping("/{linkId}/set-primary")
    @PreAuthorize("hasPermission(null, 'catalog.items.link_suppliers')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPrimary(
            @PathVariable String itemId,
            @PathVariable String linkId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        itemSupplierLinkService.setPrimaryLink(TenantRequestIds.resolveBusinessId(request), itemId, linkId);
    }
}
