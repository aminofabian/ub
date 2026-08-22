package zelisline.ub.ai.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.AiChatRequest;
import zelisline.ub.ai.api.dto.AiChatResponse;
import zelisline.ub.ai.api.dto.AiFeedbackRequest;
import zelisline.ub.ai.api.dto.AiRouteGuideResponse;
import zelisline.ub.ai.api.dto.AiStatusResponse;
import zelisline.ub.ai.api.dto.PriceRadarResponse;
import zelisline.ub.ai.api.dto.StorefrontDesignSuggestRequest;
import zelisline.ub.ai.api.dto.StorefrontDesignSuggestResponse;
import zelisline.ub.ai.application.GuideChatService;
import zelisline.ub.ai.application.PriceRadarService;
import zelisline.ub.ai.application.RouteGuideCatalog;
import zelisline.ub.ai.application.StorefrontDesignAiService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final GuideChatService guideChatService;
    private final RouteGuideCatalog routeGuideCatalog;
    private final PriceRadarService priceRadarService;
    private final StorefrontDesignAiService storefrontDesignAiService;

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public AiStatusResponse status(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return guideChatService.status();
    }

    @GetMapping("/route-guide")
    @PreAuthorize("isAuthenticated()")
    public AiRouteGuideResponse routeGuide(
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String surface,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        RouteGuideCatalog.RouteGuide guide = routeGuideCatalog.resolve(route, surface);
        return new AiRouteGuideResponse(guide.surface(), guide.title(), guide.summary(), guide.suggestions());
    }

    @GetMapping("/price-radar")
    @PreAuthorize(
            "hasPermission(null, 'pricing.read') or hasPermission(null, 'purchasing.path_b.read') "
                    + "or hasPermission(null, 'purchasing.path_b.write')")
    public PriceRadarResponse priceRadar(
            @RequestParam String itemId,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) java.math.BigDecimal unitCost,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return priceRadarService.radar(
                TenantRequestIds.resolveBusinessId(request),
                itemId,
                supplierId,
                branchId,
                unitCost);
    }

    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public AiChatResponse chat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return guideChatService.chat(businessId, user.userId(), user.branchId(), body);
    }

    @PostMapping("/feedback")
    @PreAuthorize("isAuthenticated()")
    public void feedback(@Valid @RequestBody AiFeedbackRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        guideChatService.feedback(businessId, body);
    }

    @PostMapping("/storefront-design/suggest")
    @PreAuthorize("isAuthenticated()")
    public StorefrontDesignSuggestResponse suggest(
            @Valid @RequestBody StorefrontDesignSuggestRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return storefrontDesignAiService.suggest(businessId, user.userId(), body);
    }
}
