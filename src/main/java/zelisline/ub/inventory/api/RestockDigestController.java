package zelisline.ub.inventory.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
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
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.AcceptRestockRunRequest;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.AcceptRestockRunResponse;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.RestockActiveRunSummary;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.RestockPrepResponse;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.RestockRunListRow;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.RestockRunResponse;
import zelisline.ub.inventory.api.dto.RestockDigestDtos.SnoozeRestockSuggestionRequest;
import zelisline.ub.inventory.application.RestockDigestService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/restock")
@RequiredArgsConstructor
public class RestockDigestController {

    private final RestockDigestService restockDigestService;
    private final RequestPermissionService requestPermissionService;

    /** Manual generate for testing / catch-up. Returns the existing run if already generated. */
    @PostMapping("/runs/generate")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public RestockRunResponse generate(
            @RequestParam String branchId,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate runDate,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.generateForBranch(
                businessId, branchId.trim(), runDate, "manual");
    }

    @GetMapping("/runs")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read') or hasPermission(null, 'order_pad.read')")
    public List<RestockRunListRow> listRuns(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.listRuns(businessId, blankToNull(branchId), from, to);
    }

    /** Literal route — declared before {@code /runs/{runId}} so it wins in Spring MVC. */
    @GetMapping("/runs/latest")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read') or hasPermission(null, 'order_pad.read')")
    public RestockRunResponse latest(
            @RequestParam String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.getLatestForBranch(businessId, branchId.trim());
    }

    /** Lightweight "actionable run right now" check for the grocery header chip. */
    @GetMapping("/runs/active")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read') or hasPermission(null, 'order_pad.read')")
    public RestockActiveRunSummary activeRun(
            @RequestParam String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.activeRunSummary(businessId, branchId.trim());
    }

    /**
     * Clerk-facing prep view — any authenticated staff of the business may read it;
     * the payload is redacted (no unit cost / supplier / order links).
     */
    @GetMapping("/runs/{runId}/prep")
    @PreAuthorize("isAuthenticated()")
    public RestockPrepResponse prep(@PathVariable String runId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.prepRun(businessId, runId.trim());
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.read') or hasPermission(null, 'order_pad.read')")
    public RestockRunResponse getRun(@PathVariable String runId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.getRun(businessId, runId.trim());
    }

    /** Accept pending lines into draft POs + order pad. Idempotent per line. */
    @PostMapping("/runs/{runId}/accept")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write') or hasPermission(null, 'order_pad.write')")
    public AcceptRestockRunResponse accept(
            @PathVariable String runId,
            @Valid @RequestBody AcceptRestockRunRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        boolean canWritePo = requestPermissionService.hasPermission(
                principal.roleId(), "purchasing.path_a.write");
        boolean canWritePad = requestPermissionService.hasPermission(
                principal.roleId(), "order_pad.write");
        return restockDigestService.acceptRun(
                businessId, runId.trim(), principal.userId(), canWritePo, canWritePad, body);
    }

    /** Dismiss / snooze mutate the list, so they need a write grant — not just read. */
    @PostMapping("/suggestions/{suggestionId}/dismiss")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write') or hasPermission(null, 'order_pad.write')")
    public RestockRunResponse dismiss(
            @PathVariable String suggestionId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return restockDigestService.dismissSuggestion(businessId, suggestionId.trim());
    }

    @PostMapping("/suggestions/{suggestionId}/snooze")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write') or hasPermission(null, 'order_pad.write')")
    public RestockRunResponse snooze(
            @PathVariable String suggestionId,
            @RequestBody(required = false) SnoozeRestockSuggestionRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        int days = body != null && body.days() != null ? body.days() : 1;
        return restockDigestService.snoozeSuggestion(businessId, suggestionId.trim(), days);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
