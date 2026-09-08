package zelisline.ub.storeroom.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import zelisline.ub.storeroom.api.dto.CreateStoreItemRequest;
import zelisline.ub.storeroom.api.dto.PatchStoreItemRequest;
import zelisline.ub.storeroom.api.dto.StoreItemResponse;
import zelisline.ub.storeroom.application.StoreItemService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/store-items")
@RequiredArgsConstructor
public class StoreItemsController {

    private final StoreItemService storeItemService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<StoreItemResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return storeItemService.list(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public StoreItemResponse create(
            @Valid @RequestBody CreateStoreItemRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return storeItemService.create(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    public StoreItemResponse patch(
            @PathVariable String id,
            @Valid @RequestBody PatchStoreItemRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return storeItemService.update(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        storeItemService.delete(TenantRequestIds.resolveBusinessId(request), id);
    }
}
