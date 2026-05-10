package zelisline.ub.sales.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import zelisline.ub.sales.api.dto.ApproveDrawoutRequest;
import zelisline.ub.sales.api.dto.CreateDrawoutRequest;
import zelisline.ub.sales.api.dto.CreateRecurringItemRequest;
import zelisline.ub.sales.api.dto.DrawoutResponse;
import zelisline.ub.sales.api.dto.RecurringDrawoutItemResponse;
import zelisline.ub.sales.api.dto.RejectDrawoutRequest;
import zelisline.ub.sales.api.dto.VoidDrawoutRequest;
import zelisline.ub.sales.application.DrawoutService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequiredArgsConstructor
public class DrawoutController {

    private final DrawoutService drawoutService;

    // ========================================================================
    // INITIATE DRAWOUT
    // POST /api/v1/shifts/{shiftId}/drawouts
    // ========================================================================

    @PostMapping("/api/v1/shifts/{shiftId}/drawouts")
    @PreAuthorize("hasPermission(null, 'shifts.open') or hasPermission(null, 'shifts.close')")
    @ResponseStatus(HttpStatus.CREATED)
    public DrawoutResponse initiateDrawout(
            @PathVariable String shiftId,
            @Valid @RequestBody CreateDrawoutRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return drawoutService.initiateDrawout(
                TenantRequestIds.resolveBusinessId(request),
                shiftId,
                body,
                user.userId()
        );
    }

    // ========================================================================
    // APPROVE DRAWOUT
    // POST /api/v1/drawouts/{drawoutId}/approve
    // ========================================================================

    @PostMapping("/api/v1/drawouts/{drawoutId}/approve")
    @PreAuthorize("hasPermission(null, 'shifts.drawouts.approve')")
    public DrawoutResponse approveDrawout(
            @PathVariable String drawoutId,
            @Valid @RequestBody ApproveDrawoutRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return drawoutService.approveDrawout(
                TenantRequestIds.resolveBusinessId(request),
                drawoutId,
                body,
                user.userId()
        );
    }

    // ========================================================================
    // REJECT DRAWOUT
    // POST /api/v1/drawouts/{drawoutId}/reject
    // ========================================================================

    @PostMapping("/api/v1/drawouts/{drawoutId}/reject")
    @PreAuthorize("hasPermission(null, 'shifts.drawouts.approve')")
    public DrawoutResponse rejectDrawout(
            @PathVariable String drawoutId,
            @Valid @RequestBody RejectDrawoutRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return drawoutService.rejectDrawout(
                TenantRequestIds.resolveBusinessId(request),
                drawoutId,
                body,
                user.userId()
        );
    }

    // ========================================================================
    // VOID DRAWOUT
    // POST /api/v1/drawouts/{drawoutId}/void
    // ========================================================================

    @PostMapping("/api/v1/drawouts/{drawoutId}/void")
    @PreAuthorize("hasPermission(null, 'shifts.drawouts.approve')")
    public DrawoutResponse voidDrawout(
            @PathVariable String drawoutId,
            @Valid @RequestBody VoidDrawoutRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return drawoutService.voidDrawout(
                TenantRequestIds.resolveBusinessId(request),
                drawoutId,
                body,
                user.userId()
        );
    }

    // ========================================================================
    // LIST SHIFT DRAWOUTS
    // GET /api/v1/shifts/{shiftId}/drawouts
    // ========================================================================

    @GetMapping("/api/v1/shifts/{shiftId}/drawouts")
    @PreAuthorize("hasPermission(null, 'shifts.read')")
    public List<DrawoutResponse> listShiftDrawouts(
            @PathVariable String shiftId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return drawoutService.getShiftDrawouts(
                TenantRequestIds.resolveBusinessId(request),
                shiftId
        );
    }

    // ========================================================================
    // LIST PENDING DRAWOUTS
    // GET /api/v1/drawouts/pending
    // ========================================================================

    @GetMapping("/api/v1/drawouts/pending")
    @PreAuthorize("hasPermission(null, 'shifts.drawouts.approve')")
    public List<DrawoutResponse> listPendingDrawouts(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return drawoutService.getPendingDrawouts(
                TenantRequestIds.resolveBusinessId(request)
        );
    }

    // ========================================================================
    // LIST RECURRING ITEMS
    // GET /api/v1/recurring-items
    // ========================================================================

    @GetMapping("/api/v1/recurring-items")
    @PreAuthorize("hasPermission(null, 'shifts.read')")
    public List<RecurringDrawoutItemResponse> listRecurringItems(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return drawoutService.listRecurringItems(
                TenantRequestIds.resolveBusinessId(request)
        );
    }

    // ========================================================================
    // CREATE RECURRING ITEM
    // POST /api/v1/recurring-items
    // ========================================================================

    @PostMapping("/api/v1/recurring-items")
    @PreAuthorize("hasPermission(null, 'shifts.close')")
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringDrawoutItemResponse createRecurringItem(
            @Valid @RequestBody CreateRecurringItemRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return drawoutService.createRecurringItem(
                TenantRequestIds.resolveBusinessId(request),
                body.name(),
                body.category(),
                body.defaultAmount(),
                body.amountTolerance(),
                body.defaultDescription(),
                body.defaultRecipient(),
                body.frequency(),
                body.maxPerShift(),
                body.requiresApproval(),
                user.userId()
        );
    }
}
