package zelisline.ub.tenancy.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.MobilePublishCallbackRequest;
import zelisline.ub.tenancy.application.MobilePublishService;

@RestController
@RequestMapping("/webhooks/mobile-publish")
@RequiredArgsConstructor
@Validated
public class MobilePublishWebhookController {

    private final MobilePublishService mobilePublishService;

    @Value("${app.mobile.publish.callback-secret:}")
    private String configuredCallbackSecret;

    @PostMapping("/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportStatus(
            @RequestHeader(name = "X-Mobile-Publish-Secret", required = false) String secret,
            @Valid @RequestBody MobilePublishCallbackRequest body
    ) {
        mobilePublishService.recordCallback(
                body.slug(),
                body.status(),
                body.workflowUrl(),
                body.lastError(),
                secret,
                configuredCallbackSecret
        );
    }
}
