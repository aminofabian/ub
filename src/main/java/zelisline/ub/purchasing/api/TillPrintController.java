package zelisline.ub.purchasing.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.DispatchTillPrintRequest;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.DispatchTillPrintResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintCashierResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintClaimResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintPendingResponse;
import zelisline.ub.purchasing.application.TillPrintService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/purchasing/till-prints")
@RequiredArgsConstructor
public class TillPrintController {

    private final TillPrintService tillPrintService;

    @GetMapping("/cashiers")
    @PreAuthorize(
            "hasPermission(null, 'purchasing.path_a.read') or hasPermission(null, 'purchasing.path_a.write')")
    public List<TillPrintCashierResponse> listCashiers(
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tillPrintService.listCashiers(TenantRequestIds.resolveBusinessId(request), branchId);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public DispatchTillPrintResponse dispatch(
            @Valid @RequestBody DispatchTillPrintRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return tillPrintService.dispatch(TenantRequestIds.resolveBusinessId(request), body);
    }

    @GetMapping("/pending")
    @PreAuthorize("isAuthenticated()")
    public List<TillPrintPendingResponse> pending(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return tillPrintService.listPending(TenantRequestIds.resolveBusinessId(request), principal.userId());
    }

    @PostMapping("/{jobId}/claim")
    @PreAuthorize("isAuthenticated()")
    public TillPrintClaimResponse claim(
            @PathVariable String jobId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return tillPrintService.claim(
                TenantRequestIds.resolveBusinessId(request), principal.userId(), jobId);
    }
}
