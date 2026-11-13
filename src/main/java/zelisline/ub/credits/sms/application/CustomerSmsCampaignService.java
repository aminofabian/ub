package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceFilter;
import zelisline.ub.credits.email.domain.CustomerEmailCampaign;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CreateCustomerSmsCampaignRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CustomerSmsCampaignDetailResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CustomerSmsCampaignRecipientResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.CustomerSmsCampaignSummaryResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsAudiencePreviewRequest;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsAudiencePreviewResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsEstimateResponse;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.UpdateCustomerSmsCampaignRequest;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaign;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaignRecipient;
import zelisline.ub.credits.sms.domain.SmsSendWindow;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRecipientRepository;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRepository;
import zelisline.ub.messaging.api.dto.SmsCreditBalanceResponse;
import zelisline.ub.messaging.application.SmsCreditGuard;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSegments;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * Drafts, schedules and reports on SMS campaigns (SMS Hub, ADR 0013). This service
 * never calls the SMS provider: sending a campaign only queues it, and
 * {@link CustomerSmsCampaignRunner} delivers it in the background.
 */
@Service
@RequiredArgsConstructor
public class CustomerSmsCampaignService {

    private static final int DETAIL_RECIPIENT_LIMIT = 200;
    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(90);

    private final SmsAudienceService audienceService;
    private final SmsCampaignText campaignText;
    private final CustomerSmsCampaignRepository campaignRepository;
    private final CustomerSmsCampaignRecipientRepository recipientRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsCreditService smsCreditService;
    private final SmsCreditGuard smsCreditGuard;
    private final SaleRepository saleRepository;

    @Transactional(readOnly = true)
    public SmsAudiencePreviewResponse previewAudience(String businessId, SmsAudiencePreviewRequest request) {
        SmsAudienceService.Audience audience = audienceService.resolve(
                businessId, request.recipientMethod(), request.customerIds(), request.filter());
        return new SmsAudiencePreviewResponse(
                audience.matched().size(),
                audience.excluded().size(),
                audience.eligible().size(),
                SmsAudienceService.sample(audience.eligible()),
                SmsAudienceService.sample(audience.excluded()));
    }

    /** Text as a typical customer would receive it, with its size and cost in parts. */
    @Transactional(readOnly = true)
    public SmsEstimateResponse estimate(String businessId, String body) {
        String text = campaignText.render(body, "Customer", audienceService.shopName(businessId));
        return new SmsEstimateResponse(
                text,
                SmsSegments.units(text),
                SmsSegments.count(text),
                SmsSegments.isGsm7(text) ? "GSM" : "UNICODE",
                campaignText.unknownVariables(body));
    }

    @Transactional
    public CustomerSmsCampaignDetailResponse createDraft(
            String businessId,
            String userId,
            CreateCustomerSmsCampaignRequest request
    ) {
        validateContent(request.name(), request.body());
        String method = audienceService.normalizeMethod(request.recipientMethod());
        SmsAudienceService.Audience audience =
                audienceService.resolve(businessId, method, request.customerIds(), request.filter());
        assertSendable(audience);

        CustomerSmsCampaign campaign = new CustomerSmsCampaign();
        campaign.setBusinessId(businessId);
        campaign.setName(request.name().trim());
        campaign.setBody(request.body().trim());
        campaign.setRecipientMethod(method);
        campaign.setFilterJson(audienceService.serializeFilter(request.filter()));
        campaign.setStatus(CustomerSmsCampaign.STATUS_DRAFT);
        campaign.setCreatedByUserId(userId);
        campaign.setRecipientsTargeted(audience.matched().size());
        campaign.setRecipientsSkipped(audience.excluded().size());
        campaignRepository.save(campaign);
        saveRecipientSnapshot(campaign, audience);
        return toDetail(campaign);
    }

    @Transactional
    public CustomerSmsCampaignDetailResponse updateDraft(
            String businessId,
            String campaignId,
            UpdateCustomerSmsCampaignRequest request
    ) {
        CustomerSmsCampaign campaign = requireStatus(businessId, campaignId, CustomerSmsCampaign.STATUS_DRAFT);
        if (request.name() != null) {
            campaign.setName(request.name().trim());
        }
        if (request.body() != null) {
            campaign.setBody(request.body().trim());
        }
        validateContent(campaign.getName(), campaign.getBody());

        String method = request.recipientMethod() != null
                ? audienceService.normalizeMethod(request.recipientMethod())
                : campaign.getRecipientMethod();
        AudienceFilter filter = request.filter() != null
                ? request.filter()
                : audienceService.deserializeFilter(campaign.getFilterJson());
        List<String> customerIds = request.customerIds();
        if (customerIds == null && CustomerEmailCampaign.METHOD_SPECIFIC.equals(method)) {
            customerIds = recipientRepository
                    .findByCampaignIdOrderByCustomerNameAscPhoneAsc(
                            campaign.getId(), PageRequest.of(0, CustomerSmsCampaign.MAX_RECIPIENTS))
                    .stream()
                    .map(CustomerSmsCampaignRecipient::getCustomerId)
                    .toList();
        }
        SmsAudienceService.Audience audience =
                audienceService.resolve(businessId, method, customerIds, filter);
        assertSendable(audience);

        campaign.setRecipientMethod(method);
        campaign.setFilterJson(audienceService.serializeFilter(filter));
        campaign.setRecipientsTargeted(audience.matched().size());
        campaign.setRecipientsSkipped(audience.excluded().size());
        campaign.setRecipientsSent(0);
        campaign.setRecipientsFailed(0);
        recipientRepository.deleteByCampaignId(campaign.getId());
        recipientRepository.flush();
        campaignRepository.save(campaign);
        saveRecipientSnapshot(campaign, audience);
        return toDetail(campaign);
    }

    @Transactional(readOnly = true)
    public Page<CustomerSmsCampaignSummaryResponse> list(String businessId, Pageable pageable) {
        return campaignRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public CustomerSmsCampaignDetailResponse get(String businessId, String campaignId) {
        return toDetail(requireCampaign(businessId, campaignId));
    }

    /** Queue a draft for sending now. Refused outside the promotional sending window. */
    @Transactional
    public CustomerSmsCampaignDetailResponse sendNow(String businessId, String campaignId, String confirmPhrase) {
        CustomerSmsCampaign campaign = requireStatus(businessId, campaignId, CustomerSmsCampaign.STATUS_DRAFT);
        if (!SmsSendWindow.isOpen(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Offers can only be sent between " + SmsSendWindow.describe()
                            + ". Schedule this campaign instead.");
        }
        assertReadyToSend(campaign, confirmPhrase);
        campaign.setStatus(CustomerSmsCampaign.STATUS_QUEUED);
        campaign.setScheduledAt(Instant.now());
        campaignRepository.save(campaign);
        return toDetail(campaign);
    }

    /** Schedule a draft. A time outside the sending window moves to the next opening. */
    @Transactional
    public CustomerSmsCampaignDetailResponse schedule(
            String businessId,
            String campaignId,
            Instant scheduledAt,
            String confirmPhrase
    ) {
        CustomerSmsCampaign campaign = requireStatus(businessId, campaignId, CustomerSmsCampaign.STATUS_DRAFT);
        Instant now = Instant.now();
        if (scheduledAt == null || scheduledAt.isBefore(now.minus(Duration.ofMinutes(1)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a time in the future");
        }
        if (scheduledAt.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a time within the next 90 days");
        }
        assertReadyToSend(campaign, confirmPhrase);
        campaign.setStatus(CustomerSmsCampaign.STATUS_SCHEDULED);
        campaign.setScheduledAt(SmsSendWindow.nextOpen(scheduledAt));
        campaignRepository.save(campaign);
        return toDetail(campaign);
    }

    /**
     * Cancel a campaign. Before sending starts nothing goes out; a campaign that is
     * already sending stops at its next recipient. The campaign is kept for history.
     */
    @Transactional
    public CustomerSmsCampaignDetailResponse cancel(String businessId, String campaignId) {
        CustomerSmsCampaign campaign = requireCampaign(businessId, campaignId);
        String status = campaign.getStatus();
        if (!CustomerSmsCampaign.STATUS_DRAFT.equals(status)
                && !CustomerSmsCampaign.STATUS_QUEUED.equals(status)
                && !CustomerSmsCampaign.STATUS_SCHEDULED.equals(status)
                && !CustomerSmsCampaign.STATUS_RUNNING.equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This campaign has already finished");
        }
        // A RUNNING campaign stops at its next recipient: the sender re-reads the status.
        campaign.setStatus(CustomerSmsCampaign.STATUS_CANCELLED);
        campaign.setCompletedAt(Instant.now());
        campaignRepository.save(campaign);
        return toDetail(campaign);
    }

    private void assertReadyToSend(CustomerSmsCampaign campaign, String confirmPhrase) {
        String businessId = campaign.getBusinessId();
        if (CustomerEmailCampaign.METHOD_ALL_ELIGIBLE.equals(campaign.getRecipientMethod())
                && (confirmPhrase == null || !"SEND".equals(confirmPhrase.trim()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Type SEND to confirm sending to every customer who can be texted");
        }
        long pending = recipientRepository.countByCampaignIdAndStatus(
                campaign.getId(), CustomerSmsCampaignRecipient.STATUS_PENDING);
        if (pending == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No one in this audience can be texted");
        }
        TenantMessagingConfig messaging =
                messagingSettingsService.resolveForTest(businessId, SmsSendReason.CAMPAIGN);
        if (!messaging.secretsReadable() || !messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "SMS is not set up for this shop yet");
        }
        if (smsCreditGuard.meteringActive(businessId)) {
            long needed = pending * partsPerMessage(campaign);
            SmsCreditBalanceResponse balance = smsCreditService.getBalanceView(businessId);
            if (balance.available() < needed) {
                throw new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "This campaign needs about " + needed + " SMS credits and you have "
                                + balance.available() + ". Buy credits or narrow the audience.");
            }
        }
    }

    private int partsPerMessage(CustomerSmsCampaign campaign) {
        return SmsSegments.count(campaignText.render(
                campaign.getBody(), "Customer", audienceService.shopName(campaign.getBusinessId())));
    }

    private void saveRecipientSnapshot(CustomerSmsCampaign campaign, SmsAudienceService.Audience audience) {
        List<CustomerSmsCampaignRecipient> rows = new ArrayList<>();
        for (SmsAudienceService.Person person : audience.matched()) {
            CustomerSmsCampaignRecipient row = new CustomerSmsCampaignRecipient();
            row.setCampaignId(campaign.getId());
            row.setBusinessId(campaign.getBusinessId());
            row.setCustomerId(person.customerId());
            row.setCustomerName(person.name());
            row.setPhone(person.phoneDigits() == null ? "" : person.phoneDigits());
            if (person.skipReason() != null) {
                row.setStatus(CustomerSmsCampaignRecipient.STATUS_SKIPPED);
                row.setSkipReason(person.skipReason());
            } else {
                row.setStatus(CustomerSmsCampaignRecipient.STATUS_PENDING);
            }
            rows.add(row);
        }
        recipientRepository.saveAll(rows);
    }

    private void validateContent(String name, String body) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give this campaign a name");
        }
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
    }

    private static void assertSendable(SmsAudienceService.Audience audience) {
        if (audience.matched().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No customers match this audience");
        }
    }

    private CustomerSmsCampaign requireCampaign(String businessId, String campaignId) {
        return campaignRepository.findByIdAndBusinessId(campaignId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    private CustomerSmsCampaign requireStatus(String businessId, String campaignId, String status) {
        CustomerSmsCampaign campaign = requireCampaign(businessId, campaignId);
        if (!status.equals(campaign.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only a draft campaign can be changed or sent");
        }
        return campaign;
    }

    private CustomerSmsCampaignSummaryResponse toSummary(CustomerSmsCampaign c) {
        return new CustomerSmsCampaignSummaryResponse(
                c.getId(),
                c.getName(),
                c.getBody(),
                c.getRecipientMethod(),
                c.getStatus(),
                c.getScheduledAt(),
                c.getRecipientsTargeted(),
                c.getRecipientsSent(),
                c.getRecipientsFailed(),
                c.getRecipientsSkipped(),
                c.getCreditsUsed(),
                c.getCreatedAt(),
                c.getCompletedAt());
    }

    private CustomerSmsCampaignDetailResponse toDetail(CustomerSmsCampaign c) {
        List<CustomerSmsCampaignRecipient> rows = recipientRepository
                .findByCampaignIdOrderByCustomerNameAscPhoneAsc(
                        c.getId(), PageRequest.of(0, DETAIL_RECIPIENT_LIMIT));
        List<CustomerSmsCampaignRecipientResponse> recipients = rows.stream()
                .map(r -> new CustomerSmsCampaignRecipientResponse(
                        r.getId(),
                        r.getCustomerId(),
                        r.getCustomerName(),
                        r.getPhone(),
                        r.getStatus(),
                        r.getSkipReason(),
                        r.getError(),
                        r.getParts(),
                        r.getSentAt()))
                .toList();
        int pending = (int) recipientRepository.countByCampaignIdAndStatus(
                c.getId(), CustomerSmsCampaignRecipient.STATUS_PENDING);
        int parts = partsPerMessage(c);
        String text = campaignText.render(c.getBody(), "Customer", audienceService.shopName(c.getBusinessId()));
        return new CustomerSmsCampaignDetailResponse(
                c.getId(),
                c.getName(),
                c.getBody(),
                text,
                parts,
                c.getRecipientMethod(),
                audienceService.deserializeFilter(c.getFilterJson()),
                c.getStatus(),
                c.getScheduledAt(),
                c.getRecipientsTargeted(),
                pending,
                c.getRecipientsSent(),
                c.getRecipientsFailed(),
                c.getRecipientsSkipped(),
                c.getCreditsUsed(),
                pending * parts,
                boughtWithin48Hours(c),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getStartedAt(),
                c.getCompletedAt(),
                recipients);
    }

    /** How many texted customers bought in the 48 hours after sending began; null before then. */
    private Integer boughtWithin48Hours(CustomerSmsCampaign c) {
        if (!CustomerSmsCampaign.STATUS_COMPLETED.equals(c.getStatus()) || c.getStartedAt() == null) {
            return null;
        }
        List<String> buyers = saleRepository.customerIdsWithSaleBetween(
                c.getBusinessId(), c.getStartedAt(), c.getStartedAt().plus(Duration.ofHours(48)));
        if (buyers.isEmpty()) {
            return 0;
        }
        Set<String> buyerIds = new HashSet<>(buyers);
        int count = 0;
        for (CustomerSmsCampaignRecipient row : recipientRepository.findByCampaignIdAndStatus(
                c.getId(), CustomerSmsCampaignRecipient.STATUS_SENT)) {
            if (buyerIds.contains(row.getCustomerId())) {
                count++;
            }
        }
        return count;
    }
}
