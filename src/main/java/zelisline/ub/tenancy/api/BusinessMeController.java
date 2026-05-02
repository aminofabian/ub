package zelisline.ub.tenancy.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.BusinessResponse;
import zelisline.ub.tenancy.api.dto.UpdateBusinessRequest;
import zelisline.ub.tenancy.application.TenancyService;

@Validated
@RestController
@RequestMapping("/api/v1/businesses/me")
@RequiredArgsConstructor
public class BusinessMeController {

    private final TenancyService tenancyService;

    @GetMapping
    public BusinessResponse getMyBusiness(HttpServletRequest request) {
        return tenancyService.getBusinessForTenant(TenantRequestIds.resolveBusinessId(request));
    }

    @PatchMapping
    public BusinessResponse updateMyBusiness(
            HttpServletRequest request,
            @Valid @RequestBody UpdateBusinessRequest updateRequest
    ) {
        return tenancyService.updateBusinessForTenant(
                TenantRequestIds.resolveBusinessId(request),
                updateRequest
        );
    }
}
