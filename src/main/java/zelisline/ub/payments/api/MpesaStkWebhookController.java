package zelisline.ub.payments.api;

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
import zelisline.ub.credits.api.dto.MpesaSimulateWebhookRequest;
import zelisline.ub.credits.application.MpesaStkIntentService;

@RestController
@RequestMapping("/webhooks/mpesa/stk")
@RequiredArgsConstructor
@Validated
public class MpesaStkWebhookController {

    private final MpesaStkIntentService mpesaStkIntentService;

    @Value("${app.payments.mpesa-stk-simulate-secret:}")
    private String configuredSimulateSecret;

    @PostMapping("/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeSimulated(
            @RequestHeader(name = "X-Mpesa-Simulate-Secret", required = false) String secret,
            @Valid @RequestBody MpesaSimulateWebhookRequest body
    ) {
        mpesaStkIntentService.fulfillWalletTopUpSimulated(body.businessId(), body.intentId(), secret, configuredSimulateSecret);
    }
}
