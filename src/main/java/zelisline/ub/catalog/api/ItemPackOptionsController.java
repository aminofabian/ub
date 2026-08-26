package zelisline.ub.catalog.api;

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
import zelisline.ub.catalog.api.dto.CreateItemPackOptionRequest;
import zelisline.ub.catalog.api.dto.ItemPackOptionResponse;
import zelisline.ub.catalog.api.dto.PatchItemPackOptionRequest;
import zelisline.ub.catalog.application.ItemPackOptionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/items/{itemId}/pack-options")
@RequiredArgsConstructor
public class ItemPackOptionsController {

    private final ItemPackOptionService itemPackOptionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<ItemPackOptionResponse> list(
            @PathVariable String itemId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemPackOptionService.listPackOptions(TenantRequestIds.resolveBusinessId(request), itemId);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemPackOptionResponse create(
            @PathVariable String itemId,
            @Valid @RequestBody CreateItemPackOptionRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemPackOptionService.createPackOption(TenantRequestIds.resolveBusinessId(request), itemId, body);
    }

    @PatchMapping("/{optionId}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    public ItemPackOptionResponse patch(
            @PathVariable String itemId,
            @PathVariable String optionId,
            @Valid @RequestBody PatchItemPackOptionRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemPackOptionService.patchPackOption(
                TenantRequestIds.resolveBusinessId(request), itemId, optionId, body);
    }

    @DeleteMapping("/{optionId}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String itemId,
            @PathVariable String optionId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        itemPackOptionService.deletePackOption(TenantRequestIds.resolveBusinessId(request), itemId, optionId);
    }
}
