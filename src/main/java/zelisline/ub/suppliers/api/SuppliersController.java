package zelisline.ub.suppliers.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import zelisline.ub.suppliers.api.dto.CreateSupplierContactRequest;
import zelisline.ub.suppliers.api.dto.CreateSupplierRequest;
import zelisline.ub.suppliers.api.dto.PatchSupplierContactRequest;
import zelisline.ub.suppliers.api.dto.PatchSupplierRequest;
import zelisline.ub.suppliers.api.dto.SupplierContactResponse;
import zelisline.ub.suppliers.api.dto.SupplierResponse;
import zelisline.ub.suppliers.api.dto.SupplierItemLinkResponse;
import zelisline.ub.suppliers.application.ItemSupplierLinkService;
import zelisline.ub.suppliers.application.SupplierService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
public class SuppliersController {

    private final SupplierService supplierService;
    private final ItemSupplierLinkService itemSupplierLinkService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'suppliers.read')")
    public Page<SupplierResponse> list(Pageable pageable, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplierService.listSuppliers(TenantRequestIds.resolveBusinessId(request), pageable);
    }

    @GetMapping("/{supplierId}")
    @PreAuthorize("hasPermission(null, 'suppliers.read')")
    public SupplierResponse get(@PathVariable String supplierId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplierService.getSupplier(TenantRequestIds.resolveBusinessId(request), supplierId);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'suppliers.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierResponse create(@Valid @RequestBody CreateSupplierRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplierService.createSupplier(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{supplierId}")
    @PreAuthorize("hasPermission(null, 'suppliers.write')")
    public SupplierResponse patch(
            @PathVariable String supplierId,
            @Valid @RequestBody PatchSupplierRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierService.patchSupplier(TenantRequestIds.resolveBusinessId(request), supplierId, body);
    }

    @GetMapping("/{supplierId}/item-links")
    @PreAuthorize("hasPermission(null, 'suppliers.read') and hasPermission(null, 'catalog.items.read')")
    public List<SupplierItemLinkResponse> listItemLinks(
            @PathVariable String supplierId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemSupplierLinkService.listLinksForSupplier(
                TenantRequestIds.resolveBusinessId(request), supplierId);
    }

    @GetMapping("/{supplierId}/contacts")
    @PreAuthorize("hasPermission(null, 'suppliers.read')")
    public List<SupplierContactResponse> listContacts(
            @PathVariable String supplierId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierService.listContacts(TenantRequestIds.resolveBusinessId(request), supplierId);
    }

    @PostMapping("/{supplierId}/contacts")
    @PreAuthorize("hasPermission(null, 'suppliers.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierContactResponse addContact(
            @PathVariable String supplierId,
            @Valid @RequestBody CreateSupplierContactRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierService.addContact(TenantRequestIds.resolveBusinessId(request), supplierId, body);
    }

    @PatchMapping("/{supplierId}/contacts/{contactId}")
    @PreAuthorize("hasPermission(null, 'suppliers.write')")
    public SupplierContactResponse patchContact(
            @PathVariable String supplierId,
            @PathVariable String contactId,
            @Valid @RequestBody PatchSupplierContactRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierService.patchContact(
                TenantRequestIds.resolveBusinessId(request), supplierId, contactId, body);
    }

    @DeleteMapping("/{supplierId}/contacts/{contactId}")
    @PreAuthorize("hasPermission(null, 'suppliers.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContact(
            @PathVariable String supplierId,
            @PathVariable String contactId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        supplierService.deleteContact(TenantRequestIds.resolveBusinessId(request), supplierId, contactId);
    }
}
