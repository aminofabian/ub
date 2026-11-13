package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.TestSmsResponse;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaign;
import zelisline.ub.messaging.application.SmsCreditsDepletedException;
import zelisline.ub.messaging.application.SmsMessageLogService;
import zelisline.ub.messaging.application.SmsSendContext;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSegments;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;

/**
 * Sends campaign wording to one number so staff can read it on a real phone before it
 * goes to customers (SMS Hub, ADR 0013). A test is the shop texting itself: it is
 * logged as transactional (so it does not use up that number's weekly offer), it costs
 * credits like any text, and it is limited per day because it bypasses consent.
 */
@Service
@RequiredArgsConstructor
public class SmsCampaignTestSender {

    public static final int MAX_TESTS_PER_DAY = 20;
    static final String REFERENCE_PREFIX = "campaign-test:";

    private final SmsCampaignText campaignText;
    private final SmsAudienceService audienceService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;
    private final SmsMessageLogService messageLog;

    public TestSmsResponse send(String businessId, String body, String rawPhone) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write the message first");
        }
        if (body.length() > CustomerSmsCampaign.MAX_BODY) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message is too long (max " + CustomerSmsCampaign.MAX_BODY + " characters)");
        }
        List<String> unknown = campaignText.unknownVariables(body);
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unsupported variables: " + String.join(", ", unknown));
        }
        String msisdn = KenyanPhoneForms.toMsisdn254(rawPhone);
        if (msisdn == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter a Kenyan mobile number, for example 0712 345 678");
        }
        TenantMessagingConfig messaging =
                messagingSettingsService.resolveForTest(businessId, SmsSendReason.CAMPAIGN);
        if (!messaging.secretsReadable() || !messaging.smsConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SMS is not set up for this shop yet");
        }
        long recent = messageLog.countByReferencePrefixSince(
                businessId, REFERENCE_PREFIX, Instant.now().minus(Duration.ofHours(24)));
        if (recent >= MAX_TESTS_PER_DAY) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "You have sent " + MAX_TESTS_PER_DAY + " test texts in the last 24 hours. Try again tomorrow.");
        }

        String text = campaignText.render(body.trim(), null, audienceService.shopName(businessId));
        int parts = SmsSegments.count(text);
        SmsMessagingClient.SendResult result;
        try (SmsSendContext.Scope ignored = SmsSendContext.transactional(null)) {
            result = smsMessagingClient.sendText(
                    messaging, "+" + msisdn, text, SmsSendReason.CAMPAIGN, REFERENCE_PREFIX + UUID.randomUUID());
        } catch (SmsCreditsDepletedException ex) {
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED, "You are out of SMS credits. Buy credits, then test again.");
        }
        return new TestSmsResponse(
                result.sent(),
                KenyanPhoneForms.toLocal07(msisdn),
                text,
                parts,
                result.sent() ? null : "The SMS provider did not accept this message");
    }
}
