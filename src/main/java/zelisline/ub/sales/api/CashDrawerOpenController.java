package zelisline.ub.sales.api;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.sales.api.dto.CashDrawerOpenLogResponse;
import zelisline.ub.sales.api.dto.CashDrawerOpenResponse;
import zelisline.ub.sales.api.dto.PatchCashDrawerOpenRequest;
import zelisline.ub.sales.api.dto.PostCashDrawerOpenRequest;
import zelisline.ub.sales.application.CashDrawerOpenService;
import zelisline.ub.sales.application.RecordedCashDrawerOpen;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.application.TillDeviceService;

@Validated
@RestController
@RequestMapping("/api/v1/cash-drawer/opens")
@RequiredArgsConstructor
public class CashDrawerOpenController {

    private final CashDrawerOpenService cashDrawerOpenService;
    private final BranchResolutionService branchResolutionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<CashDrawerOpenResponse> record(
            @Valid @RequestBody PostCashDrawerOpenRequest body,
            HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String branchId = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        PostCashDrawerOpenRequest safe = new PostCashDrawerOpenRequest(
                body.clientEventId(),
                body.openedAt(),
                body.source(),
                body.reason(),
                branchId,
                body.shiftId(),
                body.saleId(),
                body.sensorConfirmed());
        try {
            RecordedCashDrawerOpen recorded = cashDrawerOpenService.record(
                    TenantRequestIds.resolveBusinessId(request),
                    principal.userId(),
                    request.getHeader(TillDeviceService.TILL_DEVICE_HEADER),
                    safe);
            HttpStatus status = recorded.created() ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status).body(recorded.open());
        } catch (DataIntegrityViolationException ex) {
            try {
                return ResponseEntity.ok(cashDrawerOpenService.find(
                        TenantRequestIds.resolveBusinessId(request),
                        safe.clientEventId()));
            } catch (ResponseStatusException missing) {
                throw ex;
            }
        }
    }

    @PatchMapping("/{clientEventId}")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public CashDrawerOpenResponse close(
            @PathVariable @NotBlank String clientEventId,
            @Valid @RequestBody PatchCashDrawerOpenRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return cashDrawerOpenService.close(
                TenantRequestIds.resolveBusinessId(request),
                clientEventId,
                body);
    }

    @GetMapping("/log")
    @PreAuthorize("""
            hasPermission(null, 'sales.sell')
            or hasPermission(null, 'shifts.read')
            or hasPermission(null, 'shifts.open')
            or hasPermission(null, 'shifts.close')
            """)
    public CashDrawerOpenLogResponse log(
            @RequestParam @NotBlank String shiftId,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return cashDrawerOpenService.log(TenantRequestIds.resolveBusinessId(request), shiftId);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'shifts.read')")
    public List<CashDrawerOpenResponse> list(
            @RequestParam @NotBlank String shiftId,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return cashDrawerOpenService.listForShift(
                TenantRequestIds.resolveBusinessId(request),
                shiftId);
    }
}
