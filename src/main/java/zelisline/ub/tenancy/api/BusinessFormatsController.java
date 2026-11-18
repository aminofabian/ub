package zelisline.ub.tenancy.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.BusinessFormatsResponse;
import zelisline.ub.tenancy.api.dto.ChangeBusinessFormatsRequest;
import zelisline.ub.tenancy.application.BusinessFormatsService;

/**
 * Add, remove or change the shop formats a business runs, after onboarding (a restaurant that also
 * opens a bar or lets rooms). Tenant-scoped to the caller's own business; managing settings only.
 * Removing a format with live work is refused by the service (409).
 */
@RestController
@RequestMapping("/api/v1/businesses/me/formats")
@RequiredArgsConstructor
public class BusinessFormatsController {

    private static final String MANAGE_SETTINGS = "hasPermission(null, 'business.manage_settings')";

    private final BusinessFormatsService service;

    @GetMapping
    @PreAuthorize(MANAGE_SETTINGS)
    public BusinessFormatsResponse get(HttpServletRequest request) {
        return toResponse(service.describe(TenantRequestIds.resolveBusinessId(request)));
    }

    @PutMapping
    @PreAuthorize(MANAGE_SETTINGS)
    public BusinessFormatsResponse change(
            HttpServletRequest request,
            @Valid @RequestBody ChangeBusinessFormatsRequest body) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        service.change(businessId, body.storeTypes());
        return toResponse(service.describe(businessId));
    }

    private static BusinessFormatsResponse toResponse(List<BusinessFormatsService.FormatState> states) {
        List<String> selected = states.stream().filter(BusinessFormatsService.FormatState::selected)
                .map(BusinessFormatsService.FormatState::code).toList();
        List<BusinessFormatsResponse.FormatOption> formats = states.stream()
                .map(s -> new BusinessFormatsResponse.FormatOption(
                        s.code(), s.selected(), s.removable(), s.lockedReason()))
                .toList();
        return new BusinessFormatsResponse(selected, formats);
    }
}
