package zelisline.ub.pharmacy.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.api.dto.BulkClassifyMedicinesRequest;
import zelisline.ub.pharmacy.api.dto.BulkMarkMedicinesRequest;
import zelisline.ub.pharmacy.api.dto.BulkMedicinesResult;
import zelisline.ub.pharmacy.api.dto.ConfirmSuggestedClassesRequest;
import zelisline.ub.pharmacy.api.dto.MedicineClassCountsResponse;
import zelisline.ub.pharmacy.api.dto.MedicineDetailsResponse;
import zelisline.ub.pharmacy.api.dto.MedicinePageResponse;
import zelisline.ub.pharmacy.api.dto.UpsertMedicineDetailsRequest;
import zelisline.ub.pharmacy.application.MedicineDetailsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Which catalogue items are medicines, their attributes and their dispensing class.
 * The business comes from the request, never from the body.
 */
@Validated
@RestController
@RequestMapping("/api/v1/pharmacy/medicines")
@RequiredArgsConstructor
public class MedicinesController {

    private final MedicineDetailsService medicineDetailsService;
    private final zelisline.ub.pharmacy.application.ProductReviewService productReviewService;
    private final zelisline.ub.pharmacy.application.DispensingGate dispensingGate;

    /**
     * Which of these products are medicines. The receiving screens ask before posting a delivery,
     * so they can collect the lot number and expiry the server will require.
     */
    @PostMapping("/among")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public java.util.List<zelisline.ub.pharmacy.api.dto.MedicineAmongResponse> among(
            @Valid @RequestBody zelisline.ub.pharmacy.api.dto.DispensingCheckRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return dispensingGate.standings(TenantRequestIds.resolveBusinessId(request), body.itemIds()).stream()
                .map(s -> new zelisline.ub.pharmacy.api.dto.MedicineAmongResponse(s.itemId(), s.name()))
                .toList();
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public MedicinePageResponse list(
            @RequestParam(required = false) String dispensingClass,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return medicineDetailsService.list(
                TenantRequestIds.resolveBusinessId(request), dispensingClass, q, page, size);
    }

    /** Products added since the last check that are not marked as medicines. */
    @GetMapping("/to-review")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public zelisline.ub.pharmacy.application.ProductReviewService.ToReview toReview(HttpServletRequest request) {
        return productReviewService.toReview(TenantRequestIds.resolveBusinessId(request));
    }

    /** Confirms that what is left on that list is not a medicine. */
    @PostMapping("/to-review/done")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public zelisline.ub.pharmacy.application.ProductReviewService.ToReview reviewed(HttpServletRequest request) {
        zelisline.ub.platform.security.TenantPrincipal user =
                zelisline.ub.platform.security.CurrentTenantUser.requireHuman(request);
        return productReviewService.markReviewed(TenantRequestIds.resolveBusinessId(request), user.userId());
    }

    @GetMapping("/counts")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public MedicineClassCountsResponse counts(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return medicineDetailsService.counts(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/{itemId}")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public MedicineDetailsResponse get(@PathVariable String itemId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return medicineDetailsService.get(TenantRequestIds.resolveBusinessId(request), itemId);
    }

    @PutMapping("/{itemId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public MedicineDetailsResponse upsert(
            @PathVariable String itemId,
            @Valid @RequestBody UpsertMedicineDetailsRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return medicineDetailsService.upsert(
                TenantRequestIds.resolveBusinessId(request), itemId, body, user.userId());
    }

    @DeleteMapping("/{itemId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmark(@PathVariable String itemId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        medicineDetailsService.unmark(TenantRequestIds.resolveBusinessId(request), itemId, user.userId());
    }

    @PostMapping("/classify")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public BulkMedicinesResult classify(
            @Valid @RequestBody BulkClassifyMedicinesRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return medicineDetailsService.bulkClassify(
                TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }

    /** Accepts the catalogue's suggested class for the chosen unclassified medicines. */
    @PostMapping("/confirm-suggested")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public BulkMedicinesResult confirmSuggested(
            @Valid @RequestBody ConfirmSuggestedClassesRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return medicineDetailsService.confirmSuggested(
                TenantRequestIds.resolveBusinessId(request), body.itemIds(), user.userId());
    }

    @PostMapping("/mark")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public BulkMedicinesResult mark(
            @Valid @RequestBody BulkMarkMedicinesRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return medicineDetailsService.bulkMark(
                TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }
}
