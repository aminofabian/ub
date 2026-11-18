package zelisline.ub.platform.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.BusinessTypeResponse;
import zelisline.ub.platform.application.BusinessTypeCatalogService;

/**
 * The merged, enabled business-type catalogue for merchant-facing surfaces (onboarding, business
 * hub, storefront). Platform-global and public ({@code /api/v1/public/**} is permitAll in
 * SecurityConfig) — it carries no tenant or customer data. Hidden ({@code enabled=false}) types are
 * excluded. See ADR 0029.
 */
@RestController
@RequestMapping("/api/v1/public/business-types")
@RequiredArgsConstructor
public class PublicBusinessTypesController {

    private final BusinessTypeCatalogService service;

    @GetMapping
    public List<BusinessTypeResponse> list() {
        return service.list(true);
    }
}
