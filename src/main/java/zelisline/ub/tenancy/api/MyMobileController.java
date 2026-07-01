package zelisline.ub.tenancy.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.MobilePublishRequest;
import zelisline.ub.tenancy.api.dto.MobilePublishStatusResponse;
import zelisline.ub.tenancy.api.dto.MyMobileConfigResponse;
import zelisline.ub.tenancy.application.MobilePublishService;
import zelisline.ub.tenancy.application.MyMobileConfigService;

/**
 * Authenticated mobile distribution for the current business — provisions bundle IDs,
 * deep links, and an EAS tenant profile export on first access.
 */
@RestController
@RequestMapping("/api/v1/businesses/me/mobile")
@RequiredArgsConstructor
public class MyMobileController {

    private static final String REQUIRES_MANAGE_SETTINGS =
            "hasPermission(null, 'business.manage_settings')";

    private final MyMobileConfigService myMobileConfigService;
    private final MobilePublishService mobilePublishService;

    @GetMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public MyMobileConfigResponse getMyMobileConfig(HttpServletRequest request) {
        return myMobileConfigService.getOrProvisionForBusiness(
                TenantRequestIds.resolveBusinessId(request)
        );
    }

    @GetMapping("/publish")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public MobilePublishStatusResponse getPublishStatus(HttpServletRequest request) {
        return mobilePublishService.getStatus(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/publish")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public MobilePublishStatusResponse requestPublish(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) MobilePublishRequest body
    ) {
        String app = body == null ? "shopper" : body.resolvedApp();
        String platform = body == null ? "all" : body.resolvedPlatform();
        return mobilePublishService.requestPublish(
                TenantRequestIds.resolveBusinessId(request),
                app,
                platform
        );
    }
}
