package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.sms.domain.BusinessSmsHubSettings;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaign;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaignRecipient;
import zelisline.ub.credits.sms.domain.SmsPhones;
import zelisline.ub.credits.sms.domain.SmsSendWindow;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRecipientRepository;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRepository;
import zelisline.ub.messaging.application.SmsCreditsDepletedException;
import zelisline.ub.messaging.application.SmsMessageLogService;
import zelisline.ub.messaging.application.SmsSendContext;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSegments;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;

/**
 * Delivers a claimed SMS campaign, one recipient at a time, outside any long
 * transaction (SMS Hub, ADR 0013).
 *
 * <p>Never sends twice: a recipient is saved as SENDING before the provider call and
 * only PENDING rows are picked up, so a sender that dies mid-call leaves a SENDING row
 * that {@link #recoverStalled()} fails rather than retries; the message log is also
 * checked by reference before each send.
 */
@Component
@RequiredArgsConstructor
public class CustomerSmsCampaignRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSmsCampaignRunner.class);
    private static final long SEND_PAUSE_MS = 60L;
    private static final int HEARTBEAT_EVERY = 20;
    private static final Duration STALLED_AFTER = Duration.ofMinutes(10);

    private final CustomerSmsCampaignRepository campaignRepository;
    private final CustomerSmsCampaignRecipientRepository recipientRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsHubSettingsService settingsService;
    private final SmsAudienceService audienceService;
    private final SmsCampaignText campaignText;
    private final SmsPromoGuard promoGuard;
    private final SmsMessagingClient smsMessagingClient;
    private final SmsMessageLogService messageLog;

    /** Runs on the async executor so a long campaign never blocks the scheduler thread. */
    @Async
    public void runAsync(String campaignId) {
        try {
            run(campaignId);
        } catch (RuntimeException ex) {
            log.error("sms_campaign run failed campaign={}: {}", campaignId, ex.getMessage(), ex);
        }
    }

    /** Sends every PENDING recipient of a campaign already claimed as RUNNING. */
    public void run(String campaignId) {
        CustomerSmsCampaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null || !CustomerSmsCampaign.STATUS_RUNNING.equals(campaign.getStatus())) {
            return;
        }
        String businessId = campaign.getBusinessId();
        TenantMessagingConfig messaging =
                messagingSettingsService.resolveForTest(businessId, SmsSendReason.CAMPAIGN);
        if (!messaging.secretsReadable() || !messaging.smsConfigured()) {
            campaignRepository.finishRunning(campaignId, CustomerSmsCampaign.STATUS_FAILED, Instant.now());
            log.warn("sms_campaign {} failed: SMS not configured for business={}", campaignId, businessId);
            return;
        }
        campaignRepository.markStarted(campaignId, Instant.now());

        BusinessSmsHubSettings settings = settingsService.load(businessId);
        String shopName = audienceService.shopName(businessId);
        List<CustomerSmsCampaignRecipient> pending = recipientRepository
                .findByCampaignIdAndStatusOrderByCustomerNameAscPhoneAsc(
                        campaignId, CustomerSmsCampaignRecipient.STATUS_PENDING);

        int handled = 0;
        boolean outOfCredits = false;
        for (CustomerSmsCampaignRecipient row : pending) {
            Instant now = Instant.now();
            if (outOfCredits) {
                skip(row, SmsPromoGuard.SKIP_NO_CREDITS);
                continue;
            }
            if (handled % HEARTBEAT_EVERY == 0 && !reportProgress(campaignId)) {
                log.info("sms_campaign {} stopped: no longer running (cancelled?)", campaignId);
                return;
            }
            if (!SmsSendWindow.isOpen(now)) {
                // Window closed mid-run: the rest waits for the next opening.
                reportProgress(campaignId);
                campaignRepository.releaseRunning(
                        campaignId, CustomerSmsCampaign.STATUS_SCHEDULED, SmsSendWindow.nextOpen(now), now);
                return;
            }
            handled++;
            try {
                outOfCredits = !sendOne(campaign, row, messaging, settings, shopName, now);
            } catch (RuntimeException ex) {
                log.warn("sms_campaign {} recipient {} failed: {}", campaignId, row.getId(), ex.getMessage());
                row.setStatus(CustomerSmsCampaignRecipient.STATUS_FAILED);
                row.setError("Could not send");
                recipientRepository.save(row);
            }
            pause();
        }

        if (!reportProgress(campaignId)) {
            return;
        }
        long sent = recipientRepository.countByCampaignIdAndStatus(
                campaignId, CustomerSmsCampaignRecipient.STATUS_SENT);
        long failed = recipientRepository.countByCampaignIdAndStatus(
                campaignId, CustomerSmsCampaignRecipient.STATUS_FAILED);
        campaignRepository.finishRunning(
                campaignId,
                sent == 0 && failed > 0 ? CustomerSmsCampaign.STATUS_FAILED : CustomerSmsCampaign.STATUS_COMPLETED,
                Instant.now());
    }

    /** @return false when the shop ran out of SMS credits (the row is left skipped) */
    private boolean sendOne(
            CustomerSmsCampaign campaign,
            CustomerSmsCampaignRecipient row,
            TenantMessagingConfig messaging,
            BusinessSmsHubSettings settings,
            String shopName,
            Instant now
    ) {
        String businessId = campaign.getBusinessId();
        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(row.getCustomerId(), businessId)
                .orElse(null);
        CustomerPhone phone = customer == null ? null
                : SmsPhones.pick(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId()));
        SmsPromoGuard.Decision decision =
                promoGuard.check(businessId, customer, phone, settings.getPromoWeeklyCap(), now);
        if (!decision.allowed()) {
            skip(row, decision.reason());
            return true;
        }
        String reference = "campaign:" + campaign.getId() + ":" + row.getCustomerId();
        if (messageLog.alreadySent(businessId, reference)) {
            row.setStatus(CustomerSmsCampaignRecipient.STATUS_SENT);
            if (row.getSentAt() == null) {
                row.setSentAt(now);
            }
            recipientRepository.save(row);
            return true;
        }
        String text = campaignText.render(campaign.getBody(), customer.getName(), shopName);
        row.setPhone(decision.phoneDigits());
        row.setStatus(CustomerSmsCampaignRecipient.STATUS_SENDING);
        recipientRepository.save(row);

        SmsMessagingClient.SendResult result;
        try (SmsSendContext.Scope ignored = SmsSendContext.promotional(customer.getId(), campaign.getId())) {
            result = smsMessagingClient.sendText(
                    messaging, "+" + decision.phoneDigits(), text, SmsSendReason.CAMPAIGN, reference);
        } catch (SmsCreditsDepletedException ex) {
            skip(row, SmsPromoGuard.SKIP_NO_CREDITS);
            return false;
        }
        if (result.sent()) {
            row.setStatus(CustomerSmsCampaignRecipient.STATUS_SENT);
            row.setSentAt(Instant.now());
            row.setParts(SmsSegments.count(text));
            row.setError(null);
        } else {
            row.setStatus(CustomerSmsCampaignRecipient.STATUS_FAILED);
            row.setError(result.stub() ? "SMS is not set up" : "The SMS provider did not accept this message");
        }
        recipientRepository.save(row);
        return true;
    }

    /**
     * Campaigns left RUNNING by a sender that stopped (deploy, crash). Recipients caught
     * mid-call are failed, not retried, and the campaign is queued to continue.
     *
     * @return number of campaigns recovered
     */
    public int recoverStalled() {
        Instant now = Instant.now();
        List<CustomerSmsCampaign> stalled = campaignRepository.findStalled(now.minus(STALLED_AFTER));
        int recovered = 0;
        for (CustomerSmsCampaign campaign : stalled) {
            for (CustomerSmsCampaignRecipient row : recipientRepository.findByCampaignIdAndStatus(
                    campaign.getId(), CustomerSmsCampaignRecipient.STATUS_SENDING)) {
                row.setStatus(CustomerSmsCampaignRecipient.STATUS_FAILED);
                row.setError("Interrupted while sending; not retried");
                recipientRepository.save(row);
            }
            if (campaignRepository.releaseRunning(
                    campaign.getId(), CustomerSmsCampaign.STATUS_QUEUED, now, now) == 1) {
                recovered++;
                log.warn("sms_campaign {} recovered after a stalled send", campaign.getId());
            }
        }
        return recovered;
    }

    private void skip(CustomerSmsCampaignRecipient row, String reason) {
        row.setStatus(CustomerSmsCampaignRecipient.STATUS_SKIPPED);
        row.setSkipReason(reason);
        recipientRepository.save(row);
    }

    /** Writes heartbeat and counters. False when the campaign is no longer RUNNING. */
    private boolean reportProgress(String campaignId) {
        List<CustomerSmsCampaignRecipient> sent = recipientRepository.findByCampaignIdAndStatus(
                campaignId, CustomerSmsCampaignRecipient.STATUS_SENT);
        int credits = sent.stream().mapToInt(CustomerSmsCampaignRecipient::getParts).sum();
        int failed = (int) recipientRepository.countByCampaignIdAndStatus(
                campaignId, CustomerSmsCampaignRecipient.STATUS_FAILED);
        int skipped = (int) recipientRepository.countByCampaignIdAndStatus(
                campaignId, CustomerSmsCampaignRecipient.STATUS_SKIPPED);
        return campaignRepository.progress(
                campaignId, Instant.now(), sent.size(), failed, skipped, credits) == 1;
    }

    private static void pause() {
        try {
            Thread.sleep(SEND_PAUSE_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
