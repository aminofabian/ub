package zelisline.ub.domainoffer.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.domainoffer.api.dto.DomainOfferDtos;
import zelisline.ub.domainoffer.application.DomainOfferMessagingService;
import zelisline.ub.domainoffer.application.DomainOfferService;

/** Super Admin control room for the KES 1,000 domain + theme offer. */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/domain-offer")
@RequiredArgsConstructor
public class SuperAdminDomainOfferController {

    private final DomainOfferService domainOfferService;

    @GetMapping("/campaign")
    public DomainOfferDtos.DomainOfferCampaignResponse campaign() {
        return domainOfferService.getCampaign();
    }

    @PostMapping("/campaign/start")
    public DomainOfferDtos.DomainOfferCampaignResponse start(
            @RequestBody(required = false) DomainOfferDtos.StartCampaignRequest body
    ) {
        return domainOfferService.startCampaign(body, requireSuperAdminId());
    }

    @PostMapping("/campaign/pause")
    public DomainOfferDtos.DomainOfferCampaignResponse pause() {
        return domainOfferService.pauseCampaign(requireSuperAdminId());
    }

    @PostMapping("/campaign/test-email")
    public DomainOfferMessagingService.DeliveryResult testEmail(
            @Valid @RequestBody DomainOfferDtos.TestEmailRequest body
    ) {
        return domainOfferService.sendTestEmail(body, "Super Admin");
    }

    @GetMapping("/orders")
    public DomainOfferDtos.OrderListResponse orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "120") int limit
    ) {
        return domainOfferService.listOrders(status, q, limit);
    }

    @GetMapping("/orders/{orderId}")
    public DomainOfferDtos.DomainOfferOrderResponse order(@PathVariable String orderId) {
        return domainOfferService.getOrder(orderId);
    }

    @PostMapping("/orders/{orderId}/mark-paid")
    public DomainOfferDtos.DomainOfferOrderResponse markPaid(@PathVariable String orderId) {
        return domainOfferService.markPaid(orderId);
    }

    @PostMapping("/orders/{orderId}/assign")
    public DomainOfferDtos.DomainOfferOrderResponse assign(
            @PathVariable String orderId,
            @Valid @RequestBody DomainOfferDtos.AssignOrderRequest body
    ) {
        return domainOfferService.assign(orderId, body == null ? null : body.assignedTo());
    }

    @PostMapping("/orders/{orderId}/connect-domain")
    public DomainOfferDtos.DomainOfferOrderResponse connectDomain(@PathVariable String orderId) {
        return domainOfferService.connectDomain(orderId);
    }

    @PostMapping("/orders/{orderId}/deliver")
    public DomainOfferDtos.DomainOfferOrderResponse deliver(
            @PathVariable String orderId,
            @Valid @RequestBody DomainOfferDtos.DeliverOrderRequest body
    ) {
        return domainOfferService.deliver(orderId, body);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public DomainOfferDtos.DomainOfferOrderResponse cancel(
            @PathVariable String orderId,
            @RequestBody(required = false) DomainOfferDtos.CancelOrderRequest body
    ) {
        return domainOfferService.cancel(orderId, body == null ? null : body.reason());
    }

    @PostMapping("/orders/{orderId}/refund")
    public DomainOfferDtos.DomainOfferOrderResponse refund(
            @PathVariable String orderId,
            @RequestBody(required = false) DomainOfferDtos.RefundOrderRequest body
    ) {
        return domainOfferService.refund(orderId, body == null ? null : body.note());
    }

    @PostMapping("/orders/{orderId}/resend-link")
    public DomainOfferDtos.DomainOfferOrderResponse resendLink(@PathVariable String orderId) {
        return domainOfferService.resendLink(orderId);
    }

    @PostMapping("/orders/{orderId}/create-registration")
    @ResponseStatus(HttpStatus.CREATED)
    public DomainOfferDtos.DomainOfferOrderResponse createRegistration(@PathVariable String orderId) {
        return domainOfferService.createRegistrationOrder(orderId);
    }

    private static String requireSuperAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String saId) || saId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return saId;
    }
}
