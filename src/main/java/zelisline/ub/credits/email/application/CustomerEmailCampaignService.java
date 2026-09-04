package zelisline.ub.credits.email.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceFilter;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudiencePreviewRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudiencePreviewResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceRecipientRow;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CreateCustomerEmailCampaignRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailCampaignDetailResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailCampaignRecipientResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailCampaignSummaryResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.CustomerEmailPreviewResponse;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.PreviewCustomerEmailRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.SendCustomerEmailCampaignRequest;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.UpdateCustomerEmailCampaignRequest;
import zelisline.ub.credits.email.domain.CustomerEmailCampaign;
import zelisline.ub.credits.email.domain.CustomerEmailCampaignRecipient;
import zelisline.ub.credits.email.repository.CustomerEmailCampaignRecipientRepository;
import zelisline.ub.credits.email.repository.CustomerEmailCampaignRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.application.NotificationService;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerEmailCampaignService {

    private static final long SEND_PAUSE_MS = 40L;

    private final CustomerEmailAudienceService audienceService;
    private final CustomerEmailCampaignRepository campaignRepository;
    private final CustomerEmailCampaignRecipientRepository recipientRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public AudiencePreviewResponse previewAudience(String businessId, AudiencePreviewRequest request) {
        CustomerEmailAudienceService.ResolvedAudience audience = audienceService.resolve(
                businessId,
                request.recipientMethod(),
                request.customerIds(),
                request.filter());
        return new AudiencePreviewResponse(
                audience.matchedCount(),
                audience.excludedCount(),
                audience.finalCount(),
                audienceService.sample(audience.eligible()),
                audienceService.sample(audience.excluded()));
    }

    @Transactional
    public CustomerEmailCampaignDetailResponse createDraft(
            String businessId,
            String userId,
            CreateCustomerEmailCampaignRequest request
    ) {
        validateContent(request.name(), request.subject(), request.bodyHtml());
        List<String> unknown = CustomerEmailMerge.findUnknown(request.subject(), request.bodyHtml());
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported variables: " + String.join(", ", unknown));
        }

        String method = audienceService.normalizeMethod(request.recipientMethod());
        CustomerEmailAudienceService.ResolvedAudience audience = audienceService.resolve(
                businessId, method, request.customerIds(), request.filter());
        assertSendable(audience);

        CustomerEmailCampaign campaign = new CustomerEmailCampaign();
        campaign.setBusinessId(businessId);
        campaign.setName(request.name().trim());
        campaign.setSubject(request.subject().trim());
        campaign.setBodyHtml(request.bodyHtml());
        campaign.setRecipientMethod(method);
        campaign.setFilterJson(audienceService.serializeFilter(request.filter()));
        campaign.setStatus(CustomerEmailCampaign.STATUS_DRAFT);
        campaign.setCreatedByUserId(userId);
        campaign.setRecipientsTargeted(audience.matchedCount());
        campaign.setRecipientsSkipped(audience.excludedCount());
        campaignRepository.save(campaign);
        saveRecipientSnapshot(campaign, audience);
        return toDetail(campaign);
    }

    @Transactional
    public CustomerEmailCampaignDetailResponse updateDraft(
            String businessId,
            String campaignId,
            UpdateCustomerEmailCampaignRequest request
    ) {
        CustomerEmailCampaign campaign = requireDraft(businessId, campaignId);
        if (request.name() != null) {
            campaign.setName(request.name().trim());
        }
        if (request.subject() != null) {
            campaign.setSubject(request.subject().trim());
        }
        if (request.bodyHtml() != null) {
            campaign.setBodyHtml(request.bodyHtml());
        }
        validateContent(campaign.getName(), campaign.getSubject(), campaign.getBodyHtml());
        List<String> unknown = CustomerEmailMerge.findUnknown(campaign.getSubject(), campaign.getBodyHtml());
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported variables: " + String.join(", ", unknown));
        }

        String method = request.recipientMethod() != null
                ? audienceService.normalizeMethod(request.recipientMethod())
                : campaign.getRecipientMethod();
        AudienceFilter filter = request.filter() != null
                ? request.filter()
                : audienceService.deserializeFilter(campaign.getFilterJson());
        List<String> customerIds = request.customerIds();
        if (customerIds == null && CustomerEmailCampaign.METHOD_SPECIFIC.equals(method)) {
            customerIds = recipientRepository
                    .findByCampaignIdOrderByCustomerNameAscEmailAsc(campaign.getId())
                    .stream()
                    .map(CustomerEmailCampaignRecipient::getCustomerId)
                    .toList();
        }

        CustomerEmailAudienceService.ResolvedAudience audience =
                audienceService.resolve(businessId, method, customerIds, filter);
        assertSendable(audience);

        campaign.setRecipientMethod(method);
        campaign.setFilterJson(audienceService.serializeFilter(filter));
        campaign.setRecipientsTargeted(audience.matchedCount());
        campaign.setRecipientsSkipped(audience.excludedCount());
        campaign.setRecipientsSent(0);
        campaign.setRecipientsFailed(0);
        recipientRepository.deleteByCampaignId(campaign.getId());
        campaignRepository.save(campaign);
        saveRecipientSnapshot(campaign, audience);
        return toDetail(campaign);
    }

    @Transactional(readOnly = true)
    public Page<CustomerEmailCampaignSummaryResponse> list(String businessId, Pageable pageable) {
        return campaignRepository
                .findByBusinessIdOrderByCreatedAtDesc(businessId, pageable)
                .map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public CustomerEmailCampaignDetailResponse get(String businessId, String campaignId) {
        return toDetail(requireCampaign(businessId, campaignId));
    }

    @Transactional(readOnly = true)
    public CustomerEmailPreviewResponse previewUnpersisted(
            String businessId,
            PreviewCustomerEmailRequest request
    ) {
        validateContent("preview", request.subject(), request.bodyHtml());
        CustomerEmailAudienceService.ResolvedAudience audience = audienceService.resolve(
                businessId, request.recipientMethod(), request.customerIds(), request.filter());
        if (audience.matchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No recipients match this audience");
        }
        AudienceRecipientRow sample = pickSample(audience, request.customerId());
        return renderPreview(businessId, request.subject(), request.bodyHtml(), sample, audience);
    }

    @Transactional(readOnly = true)
    public CustomerEmailPreviewResponse previewCampaign(
            String businessId,
            String campaignId,
            String customerId
    ) {
        CustomerEmailCampaign campaign = requireCampaign(businessId, campaignId);
        CustomerEmailAudienceService.ResolvedAudience audience = audienceFromRows(campaign);
        AudienceRecipientRow sample = pickSample(audience, customerId);
        return renderPreview(businessId, campaign.getSubject(), campaign.getBodyHtml(), sample, audience);
    }

    public CustomerEmailCampaignDetailResponse send(
            String businessId,
            String campaignId,
            SendCustomerEmailCampaignRequest request
    ) {
        CustomerEmailCampaign campaign = requireDraft(businessId, campaignId);
        if (CustomerEmailCampaign.METHOD_ALL_ELIGIBLE.equals(campaign.getRecipientMethod())) {
            String phrase = request == null ? null : request.confirmPhrase();
            if (phrase == null || !"SEND".equals(phrase.trim())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Type SEND to confirm sending to all eligible customers");
            }
        }

        List<CustomerEmailCampaignRecipient> rows =
                recipientRepository.findByCampaignIdOrderByCustomerNameAscEmailAsc(campaign.getId());
        long pending = rows.stream()
                .filter(r -> CustomerEmailCampaignRecipient.STATUS_PENDING.equals(r.getStatus()))
                .count();
        if (pending == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No eligible recipients to send");
        }
        if (pending > CustomerEmailCampaign.MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Campaign exceeds " + CustomerEmailCampaign.MAX_RECIPIENTS + " recipients");
        }

        campaign.setStatus(CustomerEmailCampaign.STATUS_RUNNING);
        campaign.setStartedAt(Instant.now());
        campaignRepository.save(campaign);

        String shopName = audienceService.shopName(businessId);
        String shopUrl = audienceService.shopOrigin(businessId);
        Map<String, Customer> customers = loadCustomers(rows);
        Map<String, CreditAccount> credits = loadCredits(rows);
        Map<String, CustomerPhone> phones = loadPrimaryPhones(rows);

        int sent = 0;
        int failed = 0;
        int skipped = 0;
        boolean firstPending = true;
        for (CustomerEmailCampaignRecipient row : rows) {
            if (CustomerEmailCampaignRecipient.STATUS_SKIPPED.equals(row.getStatus())) {
                skipped++;
                continue;
            }
            if (!firstPending) {
                pauseQuietly();
            }
            firstPending = false;

            Customer customer = customers.get(row.getCustomerId());
            if (customer == null) {
                row.setStatus(CustomerEmailCampaignRecipient.STATUS_FAILED);
                row.setError("Customer no longer exists");
                failed++;
                recipientRepository.save(row);
                continue;
            }
            String liveSkip = audienceService.eligibilitySkipReason(customer);
            if (liveSkip != null) {
                row.setStatus(CustomerEmailCampaignRecipient.STATUS_SKIPPED);
                row.setSkipReason(liveSkip);
                row.setError(liveSkip);
                skipped++;
                recipientRepository.save(row);
                continue;
            }

            CreditAccount credit = credits.get(customer.getId());
            CustomerPhone phone = phones.get(customer.getId());
            CustomerEmailMerge.Context ctx = mergeContext(customer, phone, credit, shopName, shopUrl);
            CustomerEmailMerge.Result merged = CustomerEmailMerge.apply(
                    campaign.getSubject(), campaign.getBodyHtml(), ctx);
            try {
                notificationService.sendNotificationEmail(
                        row.getEmail(),
                        merged.subject(),
                        stripTags(merged.body()),
                        merged.body(),
                        shopName);
                row.setStatus(CustomerEmailCampaignRecipient.STATUS_SENT);
                row.setSentAt(Instant.now());
                sent++;
            } catch (Exception ex) {
                log.warn("Customer email campaign send failed campaign={} customer={}: {}",
                        campaign.getId(), customer.getId(), ex.getMessage());
                row.setStatus(CustomerEmailCampaignRecipient.STATUS_FAILED);
                row.setError(trimError(ex.getMessage()));
                failed++;
            }
            recipientRepository.save(row);
        }

        campaign.setRecipientsSent(sent);
        campaign.setRecipientsFailed(failed);
        campaign.setRecipientsSkipped(skipped);
        campaign.setCompletedAt(Instant.now());
        campaign.setStatus(failed > 0 && sent == 0
                ? CustomerEmailCampaign.STATUS_FAILED
                : CustomerEmailCampaign.STATUS_COMPLETED);
        campaignRepository.save(campaign);
        return toDetail(campaign);
    }

    private void saveRecipientSnapshot(
            CustomerEmailCampaign campaign,
            CustomerEmailAudienceService.ResolvedAudience audience
    ) {
        List<CustomerEmailCampaignRecipient> rows = new ArrayList<>();
        for (AudienceRecipientRow person : audience.matched()) {
            CustomerEmailCampaignRecipient row = new CustomerEmailCampaignRecipient();
            row.setCampaignId(campaign.getId());
            row.setBusinessId(campaign.getBusinessId());
            row.setCustomerId(person.customerId());
            row.setEmail(person.email() == null ? "" : person.email());
            row.setCustomerName(person.name());
            if (person.skipReason() != null) {
                row.setStatus(CustomerEmailCampaignRecipient.STATUS_SKIPPED);
                row.setSkipReason(person.skipReason());
                row.setError(person.skipReason());
            } else {
                row.setStatus(CustomerEmailCampaignRecipient.STATUS_PENDING);
            }
            rows.add(row);
        }
        if (audience.eligible().size() > CustomerEmailCampaign.MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Campaign exceeds " + CustomerEmailCampaign.MAX_RECIPIENTS + " eligible recipients");
        }
        recipientRepository.saveAll(rows);
    }

    private CustomerEmailPreviewResponse renderPreview(
            String businessId,
            String subject,
            String bodyHtml,
            AudienceRecipientRow sample,
            CustomerEmailAudienceService.ResolvedAudience audience
    ) {
        Customer customer = customerRepository.findById(sample.customerId()).orElse(null);
        CreditAccount credit = customer == null
                ? null
                : creditAccountRepository
                        .findByCustomerIdAndBusinessId(customer.getId(), businessId)
                        .orElse(null);
        CustomerPhone phone = customer == null
                ? null
                : customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId()).stream()
                        .filter(CustomerPhone::isPrimary)
                        .findFirst()
                        .orElseGet(() -> customerPhoneRepository
                                .findByCustomerIdOrderByCreatedAtAsc(customer.getId()).stream()
                                .findFirst()
                                .orElse(null));
        String shopName = audienceService.shopName(businessId);
        String shopUrl = audienceService.shopOrigin(businessId);
        CustomerEmailMerge.Context ctx = customer == null
                ? sampleContext(sample, shopName, shopUrl)
                : mergeContext(customer, phone, credit, shopName, shopUrl);
        CustomerEmailMerge.Result merged = CustomerEmailMerge.apply(subject, bodyHtml, ctx);
        return new CustomerEmailPreviewResponse(
                merged.subject(),
                merged.body(),
                sample.customerId(),
                sample.name(),
                sample.email(),
                merged.unknownTags(),
                audience.matchedCount(),
                audience.excludedCount(),
                audience.finalCount());
    }

    private static CustomerEmailMerge.Context sampleContext(
            AudienceRecipientRow sample,
            String shopName,
            String shopUrl
    ) {
        String name = sample.name() == null ? "Customer" : sample.name();
        String first = name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name;
        return new CustomerEmailMerge.Context(
                name,
                first,
                sample.email(),
                sample.phone(),
                shopName,
                shopUrl,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0);
    }

    private static CustomerEmailMerge.Context mergeContext(
            Customer customer,
            CustomerPhone phone,
            CreditAccount credit,
            String shopName,
            String shopUrl
    ) {
        String name = customer.getName() == null || customer.getName().isBlank()
                ? "Customer"
                : customer.getName();
        String first = customer.getFirstName();
        if (first == null || first.isBlank()) {
            first = name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name;
        }
        String phoneValue = null;
        if (phone != null) {
            if (phone.getPhone() != null && !phone.getPhone().isBlank()) {
                phoneValue = phone.getPhone();
            } else if (phone.getAssignedMsisdn() != null) {
                phoneValue = phone.getAssignedMsisdn();
            } else {
                phoneValue = phone.getMaskedMsisdn();
            }
        }
        return new CustomerEmailMerge.Context(
                name,
                first,
                customer.getEmail(),
                phoneValue,
                shopName,
                shopUrl,
                credit == null || credit.getWalletBalance() == null
                        ? BigDecimal.ZERO
                        : credit.getWalletBalance(),
                credit == null || credit.getBalanceOwed() == null
                        ? BigDecimal.ZERO
                        : credit.getBalanceOwed(),
                credit == null ? 0 : credit.getLoyaltyPoints());
    }

    private AudienceRecipientRow pickSample(
            CustomerEmailAudienceService.ResolvedAudience audience,
            String customerId
    ) {
        List<AudienceRecipientRow> pool = audience.eligible().isEmpty()
                ? audience.matched()
                : audience.eligible();
        if (pool.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No recipients match this audience");
        }
        if (customerId != null && !customerId.isBlank()) {
            return pool.stream()
                    .filter(r -> customerId.equals(r.customerId()))
                    .findFirst()
                    .orElse(pool.get(0));
        }
        return pool.get(0);
    }

    private CustomerEmailAudienceService.ResolvedAudience audienceFromRows(CustomerEmailCampaign campaign) {
        List<CustomerEmailCampaignRecipient> rows =
                recipientRepository.findByCampaignIdOrderByCustomerNameAscEmailAsc(campaign.getId());
        List<AudienceRecipientRow> matched = new ArrayList<>();
        List<AudienceRecipientRow> excluded = new ArrayList<>();
        List<AudienceRecipientRow> eligible = new ArrayList<>();
        for (CustomerEmailCampaignRecipient row : rows) {
            AudienceRecipientRow mapped = new AudienceRecipientRow(
                    row.getCustomerId(),
                    row.getCustomerName(),
                    row.getEmail(),
                    null,
                    row.getSkipReason());
            matched.add(mapped);
            if (CustomerEmailCampaignRecipient.STATUS_SKIPPED.equals(row.getStatus())) {
                excluded.add(mapped);
            } else {
                eligible.add(mapped);
            }
        }
        return new CustomerEmailAudienceService.ResolvedAudience(matched, excluded, eligible);
    }

    private Map<String, Customer> loadCustomers(List<CustomerEmailCampaignRecipient> rows) {
        List<String> ids = rows.stream().map(CustomerEmailCampaignRecipient::getCustomerId).toList();
        Map<String, Customer> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Customer customer : customerRepository.findAllById(ids)) {
            out.put(customer.getId(), customer);
        }
        return out;
    }

    private Map<String, CreditAccount> loadCredits(List<CustomerEmailCampaignRecipient> rows) {
        List<String> ids = rows.stream().map(CustomerEmailCampaignRecipient::getCustomerId).toList();
        Map<String, CreditAccount> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (CreditAccount account : creditAccountRepository.findByCustomerIdIn(ids)) {
            out.put(account.getCustomerId(), account);
        }
        return out;
    }

    private Map<String, CustomerPhone> loadPrimaryPhones(List<CustomerEmailCampaignRecipient> rows) {
        List<String> ids = rows.stream().map(CustomerEmailCampaignRecipient::getCustomerId).toList();
        Map<String, CustomerPhone> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (CustomerPhone phone : customerPhoneRepository.findByCustomerIdIn(ids)) {
            CustomerPhone existing = out.get(phone.getCustomerId());
            if (existing == null || (phone.isPrimary() && !existing.isPrimary())) {
                out.put(phone.getCustomerId(), phone);
            }
        }
        return out;
    }

    private CustomerEmailCampaign requireCampaign(String businessId, String campaignId) {
        return campaignRepository.findByIdAndBusinessId(campaignId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    private CustomerEmailCampaign requireDraft(String businessId, String campaignId) {
        CustomerEmailCampaign campaign = requireCampaign(businessId, campaignId);
        if (!CustomerEmailCampaign.STATUS_DRAFT.equals(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This campaign has already been run");
        }
        return campaign;
    }

    private static void assertSendable(CustomerEmailAudienceService.ResolvedAudience audience) {
        if (audience.matchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No recipients match this audience");
        }
        if (audience.finalCount() == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "All matched recipients are ineligible for marketing email");
        }
        if (audience.finalCount() > CustomerEmailCampaign.MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Too many eligible recipients (max " + CustomerEmailCampaign.MAX_RECIPIENTS + ")");
        }
    }

    private static void validateContent(String name, String subject, String bodyHtml) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Internal email name is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subject is required");
        }
        if (bodyHtml == null || bodyHtml.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTML content is required");
        }
    }

    private CustomerEmailCampaignSummaryResponse toSummary(CustomerEmailCampaign campaign) {
        return new CustomerEmailCampaignSummaryResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getSubject(),
                campaign.getRecipientMethod(),
                campaign.getStatus(),
                campaign.getRecipientsTargeted(),
                campaign.getRecipientsSent(),
                campaign.getRecipientsFailed(),
                campaign.getRecipientsSkipped(),
                campaign.getCreatedAt(),
                campaign.getCompletedAt());
    }

    private CustomerEmailCampaignDetailResponse toDetail(CustomerEmailCampaign campaign) {
        List<CustomerEmailCampaignRecipientResponse> recipients = recipientRepository
                .findByCampaignIdOrderByCustomerNameAscEmailAsc(campaign.getId())
                .stream()
                .map(r -> new CustomerEmailCampaignRecipientResponse(
                        r.getId(),
                        r.getCustomerId(),
                        r.getEmail(),
                        r.getCustomerName(),
                        r.getStatus(),
                        r.getSkipReason(),
                        r.getError(),
                        r.getSentAt()))
                .toList();
        return new CustomerEmailCampaignDetailResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getSubject(),
                campaign.getBodyHtml(),
                campaign.getRecipientMethod(),
                audienceService.deserializeFilter(campaign.getFilterJson()),
                campaign.getStatus(),
                campaign.getRecipientsTargeted(),
                campaign.getRecipientsSent(),
                campaign.getRecipientsFailed(),
                campaign.getRecipientsSkipped(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt(),
                campaign.getStartedAt(),
                campaign.getCompletedAt(),
                recipients);
    }

    private static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String trimError(String message) {
        if (message == null) {
            return "Send failed";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static void pauseQuietly() {
        try {
            Thread.sleep(SEND_PAUSE_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
