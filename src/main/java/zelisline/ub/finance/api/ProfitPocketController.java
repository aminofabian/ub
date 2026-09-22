package zelisline.ub.finance.api;

import java.time.LocalDate;

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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.api.dto.CashSurplusResponse;
import zelisline.ub.finance.api.dto.PostProfitPocketRequest;
import zelisline.ub.finance.api.dto.ProfitPocketResponse;
import zelisline.ub.finance.application.ProfitPocketService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class ProfitPocketController {

    private final ProfitPocketService profitPocketService;

    @GetMapping("/cash-surplus")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public CashSurplusResponse cashSurplus(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return profitPocketService.cashSurplus(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }

    @PostMapping("/profit-pockets")
    @PreAuthorize("hasPermission(null, 'finance.expenses.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfitPocketResponse post(
            @Valid @RequestBody PostProfitPocketRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return profitPocketService.post(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId());
    }

    @GetMapping("/profit-pockets")
    @PreAuthorize("hasPermission(null, 'finance.reports.read') or hasPermission(null, 'finance.expenses.write')")
    public List<ProfitPocketResponse> list(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return profitPocketService.list(TenantRequestIds.resolveBusinessId(request), limit);
    }

    @GetMapping("/profit-pockets/{id}")
    @PreAuthorize("hasPermission(null, 'finance.reports.read') or hasPermission(null, 'finance.expenses.write')")
    public ProfitPocketResponse get(
            @PathVariable("id") String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return profitPocketService.get(TenantRequestIds.resolveBusinessId(request), id);
    }
}
