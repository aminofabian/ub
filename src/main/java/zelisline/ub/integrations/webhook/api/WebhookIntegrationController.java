package zelisline.ub.integrations.webhook.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.webhook.api.dto.CreateWebhookSubscriptionRequest;
import zelisline.ub.integrations.webhook.api.dto.CreatedWebhookSubscriptionResponse;
import zelisline.ub.integrations.webhook.api.dto.WebhookSubscriptionResponse;
import zelisline.ub.integrations.webhook.application.WebhookSubscriptionService;
import zelisline.ub.platform.security.CurrentTenantUser;

@Validated
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
@RequiredArgsConstructor
public class WebhookIntegrationController {

    private final WebhookSubscriptionService webhookSubscriptionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'integrations.webhooks.manage')")
    public List<WebhookSubscriptionResponse> list(HttpServletRequest http) {
        return webhookSubscriptionService.list(http, CurrentTenantUser.requireHuman(http));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'integrations.webhooks.manage')")
    public CreatedWebhookSubscriptionResponse create(@Valid @RequestBody CreateWebhookSubscriptionRequest request,
            HttpServletRequest http) {
        return webhookSubscriptionService.create(http, CurrentTenantUser.requireHuman(http), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'integrations.webhooks.manage')")
    public void revoke(@PathVariable("id") String id, HttpServletRequest http) {
        webhookSubscriptionService.revoke(http, CurrentTenantUser.requireHuman(http), id);
    }

    /** DLQ replay — resets a dead delivery row to pending. */
    @PostMapping("/deliveries/{deliveryId}/replay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'integrations.webhooks.manage')")
    public void replayDelivery(@PathVariable("deliveryId") String deliveryId, HttpServletRequest http) {
        webhookSubscriptionService.replayDelivery(http, CurrentTenantUser.requireHuman(http), deliveryId);
    }
}
