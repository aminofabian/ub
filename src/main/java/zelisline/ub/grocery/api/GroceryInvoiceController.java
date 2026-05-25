package zelisline.ub.grocery.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.grocery.api.dto.CancelGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.GroceryInvoiceListResponse;
import zelisline.ub.grocery.api.dto.GroceryInvoiceResponse;
import zelisline.ub.grocery.api.dto.PayGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.PayGroceryInvoiceResponse;
import zelisline.ub.grocery.application.GroceryInvoiceService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/grocery")
@RequiredArgsConstructor
public class GroceryInvoiceController {

    private final GroceryInvoiceService service;
    private final BranchResolutionService branchResolutionService;

    @PostMapping("/invoices")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public ResponseEntity<GroceryInvoiceResponse> createInvoice(
            @Valid @RequestBody CreateGroceryInvoiceRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        CreateGroceryInvoiceRequest safe = new CreateGroceryInvoiceRequest(
                validatedBranch, body.lines(), body.notes());
        var response = service.createInvoice(
                TenantRequestIds.resolveBusinessId(request),
                safe,
                principal.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/invoices/lookup")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.read')")
    public GroceryInvoiceResponse lookupByBarcode(
            @RequestParam("barcode") String barcode,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        GroceryInvoiceResponse invoice = service.getInvoiceByBarcode(
                TenantRequestIds.resolveBusinessId(request),
                barcode
        );
        enforceOwnInvoiceForGroceryClerk(principal, invoice);
        return invoice;
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.read')")
    public GroceryInvoiceListResponse listInvoices(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveEffectiveBranch(
                businessId, branchId, principal.roleId());
        String resolvedBranch = effectiveBranch != null ? effectiveBranch
                : (principal.branchId() != null ? principal.branchId() : null);
        if (resolvedBranch == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Branch is required. Use the branch filter or contact your administrator.");
        }
        // Grocery clerks only see invoices they themselves created.
        String createdByFilter = branchResolutionService.isGroceryClerkRole(principal.roleId())
                ? principal.userId()
                : null;
        return service.listInvoices(businessId, resolvedBranch, status, createdByFilter);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.read')")
    public GroceryInvoiceResponse getInvoice(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        GroceryInvoiceResponse invoice = service.getInvoice(
                TenantRequestIds.resolveBusinessId(request),
                id
        );
        enforceOwnInvoiceForGroceryClerk(principal, invoice);
        return invoice;
    }

    @PostMapping("/invoices/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.cancel')")
    public GroceryInvoiceResponse cancelInvoice(
            @PathVariable String id,
            @Valid @RequestBody CancelGroceryInvoiceRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        // For grocery clerks, prevent cancelling someone else's invoice (404 to avoid leaking existence).
        if (branchResolutionService.isGroceryClerkRole(principal.roleId())) {
            GroceryInvoiceResponse existing = service.getInvoice(businessId, id);
            enforceOwnInvoiceForGroceryClerk(principal, existing);
        }
        return service.cancelInvoice(
                businessId,
                id,
                body,
                principal.userId()
        );
    }

    /**
     * Hide invoices created by other users from {@code grocery_clerk} principals.
     * Returns {@code 404} (rather than {@code 403}) so existence is not leaked.
     */
    private void enforceOwnInvoiceForGroceryClerk(
            TenantPrincipal principal,
            GroceryInvoiceResponse invoice
    ) {
        if (!branchResolutionService.isGroceryClerkRole(principal.roleId())) {
            return;
        }
        String creator = invoice.createdBy();
        if (creator == null || !creator.equals(principal.userId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Invoice not found");
        }
    }

    @PostMapping("/invoices/{id}/pay")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.pay')")
    public PayGroceryInvoiceResponse payInvoice(
            @PathVariable String id,
            @Valid @RequestBody PayGroceryInvoiceRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return service.payInvoice(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body,
                principal.userId()
        );
    }

    @PostMapping("/invoices/{id}/lock")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.pay')")
    public GroceryInvoiceResponse lockInvoice(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return service.lockInvoice(
                TenantRequestIds.resolveBusinessId(request),
                id,
                principal.userId()
        );
    }

    @PostMapping("/invoices/{id}/unlock")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.pay')")
    public GroceryInvoiceResponse unlockInvoice(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return service.unlockInvoice(
                TenantRequestIds.resolveBusinessId(request),
                id,
                principal.userId()
        );
    }
}
