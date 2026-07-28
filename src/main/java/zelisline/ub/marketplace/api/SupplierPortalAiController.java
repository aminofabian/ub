package zelisline.ub.marketplace.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.AiChatRequest;
import zelisline.ub.ai.api.dto.AiChatResponse;
import zelisline.ub.ai.api.dto.AiFeedbackRequest;
import zelisline.ub.ai.api.dto.AiRouteGuideResponse;
import zelisline.ub.ai.api.dto.AiStatusResponse;
import zelisline.ub.ai.application.RouteGuideCatalog;
import zelisline.ub.ai.application.SupplierGuideChatService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/ai")
@RequiredArgsConstructor
public class SupplierPortalAiController {

    private final SupplierGuideChatService supplierGuideChatService;
    private final RouteGuideCatalog routeGuideCatalog;

    @GetMapping("/status")
    @PreAuthorize("hasRole('SUPPLIER')")
    public AiStatusResponse status() {
        CurrentSupplierUser.require();
        return supplierGuideChatService.status();
    }

    @GetMapping("/route-guide")
    @PreAuthorize("hasRole('SUPPLIER')")
    public AiRouteGuideResponse routeGuide(
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String surface
    ) {
        CurrentSupplierUser.require();
        RouteGuideCatalog.RouteGuide guide = routeGuideCatalog.resolve(
                route != null ? route : "/supplier-portal",
                surface != null ? surface : "supplier-portal");
        return new AiRouteGuideResponse(guide.surface(), guide.title(), guide.summary(), guide.suggestions());
    }

    @PostMapping("/chat")
    @PreAuthorize("hasRole('SUPPLIER')")
    public AiChatResponse chat(@Valid @RequestBody AiChatRequest body) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierGuideChatService.chat(
                principal.marketplaceSupplierId(), principal.userId(), body);
    }

    @PostMapping("/feedback")
    @PreAuthorize("hasRole('SUPPLIER')")
    public void feedback(@Valid @RequestBody AiFeedbackRequest body) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        supplierGuideChatService.feedback(principal.marketplaceSupplierId(), body);
    }
}
