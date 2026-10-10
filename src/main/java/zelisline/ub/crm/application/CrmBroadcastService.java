package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.domain.CrmBroadcast;
import zelisline.ub.crm.domain.CrmBroadcastRecipient;
import zelisline.ub.crm.domain.CrmContact;
import zelisline.ub.crm.domain.CrmOutbound;
import zelisline.ub.crm.repository.CrmBroadcastRecipientRepository;
import zelisline.ub.crm.repository.CrmBroadcastRepository;
import zelisline.ub.crm.repository.CrmContactRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmOutboundRepository;

/**
 * WhatsApp broadcasts (M4): resolve an audience, create a per-recipient ledger, and fan out one
 * {@code crm_outbound} row per eligible recipient. The existing outbox drain sends them and the
 * delivery-status path mirrors Meta's callbacks back onto the ledger, so counts reconcile.
 *
 * <p>Free-form mode only reaches contacts inside the 24h customer-service window; contacts outside
 * it are marked {@code skipped} (a template is required once the window lapses). See
 * {@code docs/scopes/whatsapp-crm/M4-PLAN.md}.
 */
@Service
@RequiredArgsConstructor
public class CrmBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(CrmBroadcastService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RECIPIENTS = 1000;

    private final CrmBroadcastRepository broadcastRepository;
    private final CrmBroadcastRecipientRepository recipientRepository;
    private final CrmContactRepository contactRepository;
    private final CrmConversationRepository conversationRepository;
    private final CrmOutboundRepository outboundRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CrmDtos.BroadcastDetail create(String businessId, CrmDtos.CreateBroadcastRequest request) {
        String mode = normaliseMode(request.mode());
        boolean freeForm = CrmBroadcast.MODE_FREE_FORM.equals(mode);
        if (freeForm && isBlank(request.body())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required for free_form");
        }
        if (!freeForm && isBlank(request.templateName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateName is required for template");
        }

        Instant now = Instant.now();
        Set<String> openWindow = freeForm
                ? new HashSet<>(conversationRepository.findContactIdsWithOpenWindow(businessId, now))
                : Set.of();

        List<CrmContact> audience = resolveAudience(businessId, request.audience());

        CrmBroadcast broadcast = new CrmBroadcast();
        broadcast.setBusinessId(businessId);
        broadcast.setName(request.name().trim());
        broadcast.setMode(mode);
        broadcast.setBody(freeForm ? request.body() : null);
        broadcast.setTemplateName(freeForm ? null : request.templateName().trim());
        broadcast.setTemplateLanguage(freeForm ? null : normaliseLanguage(request.templateLanguage()));
        broadcast.setAudienceJson(writeJson(audienceJson(request.audience())));
        broadcast.setStatus(CrmBroadcast.STATUS_SENDING);
        broadcast.setUpdatedAt(now);
        CrmBroadcast saved = broadcastRepository.save(broadcast);

        int total = 0;
        int enqueued = 0;
        for (CrmContact contact : audience) {
            if (isBlank(contact.getPhoneE164())) {
                continue;
            }
            boolean eligible = !freeForm || openWindow.contains(contact.getId());
            CrmBroadcastRecipient recipient = new CrmBroadcastRecipient();
            recipient.setBroadcastId(saved.getId());
            recipient.setBusinessId(businessId);
            recipient.setContactId(contact.getId());
            recipient.setPhoneE164(contact.getPhoneE164());
            recipient.setStatus(eligible ? CrmBroadcastRecipient.STATUS_PENDING : CrmBroadcastRecipient.STATUS_SKIPPED);
            recipient.setErrorMessage(eligible ? null : "window_closed");
            recipient.setUpdatedAt(now);
            CrmBroadcastRecipient savedRecipient = recipientRepository.save(recipient);
            total++;
            if (eligible) {
                enqueueRecipient(saved, savedRecipient, mode);
                enqueued++;
            }
        }

        saved.setTotalCount(total);
        saved.setStatus(enqueued == 0 ? CrmBroadcast.STATUS_SENT : CrmBroadcast.STATUS_SENDING);
        saved.setUpdatedAt(Instant.now());
        broadcastRepository.save(saved);
        log.info("CRM broadcast {} created business={} mode={} recipients={} enqueued={}",
                saved.getId(), businessId, mode, total, enqueued);

        return toDetail(saved);
    }

    @Transactional(readOnly = true)
    public CrmDtos.BroadcastsPage list(String businessId, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<CrmBroadcast> result =
                broadcastRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(p, s));
        return new CrmDtos.BroadcastsPage(
                result.getContent().stream().map(this::toRow).toList(), p, s, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CrmDtos.BroadcastDetail get(String businessId, String id) {
        return toDetail(require(businessId, id));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<CrmContact> resolveAudience(String businessId, CrmDtos.BroadcastAudience audience) {
        String type = audience == null || isBlank(audience.type())
                ? "all"
                : audience.type().trim().toLowerCase(Locale.ROOT);
        List<CrmContact> contacts = contactRepository.findByBusinessIdOrderByCreatedAtAsc(businessId);
        if ("tag".equals(type)) {
            String tag = audience == null ? null : audience.tag();
            if (isBlank(tag)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tag is required for a tag audience");
            }
            String needle = tag.trim();
            contacts = contacts.stream().filter(c -> readTags(c).contains(needle)).toList();
        }
        return contacts.size() > MAX_RECIPIENTS ? contacts.subList(0, MAX_RECIPIENTS) : contacts;
    }

    private void enqueueRecipient(CrmBroadcast broadcast, CrmBroadcastRecipient recipient, String mode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (CrmBroadcast.MODE_TEMPLATE.equals(mode)) {
            payload.put("type", "template");
            payload.put("templateName", broadcast.getTemplateName());
            payload.put("templateLanguage", broadcast.getTemplateLanguage());
            payload.put("templateParams", List.of());
        } else {
            payload.put("type", "text");
            payload.put("body", broadcast.getBody());
        }
        payload.put("recipientId", recipient.getId());
        payload.put("broadcastId", broadcast.getId());

        CrmOutbound outbound = new CrmOutbound();
        outbound.setBusinessId(broadcast.getBusinessId());
        outbound.setBroadcastRecipientId(recipient.getId());
        outbound.setToPhoneE164(recipient.getPhoneE164());
        outbound.setStatus(CrmOutbound.STATUS_PENDING);
        outbound.setIdempotencyKey("bc:" + broadcast.getId() + ":" + recipient.getId());
        outbound.setPayloadJson(writeJson(payload));
        outboundRepository.save(outbound);
    }

    private CrmDtos.BroadcastDetail toDetail(CrmBroadcast broadcast) {
        List<CrmBroadcastRecipient> recipients =
                recipientRepository.findByBroadcastIdOrderByCreatedAtAsc(broadcast.getId());
        int pending = 0;
        int sent = 0;
        int delivered = 0;
        int read = 0;
        int failed = 0;
        int skipped = 0;
        for (CrmBroadcastRecipient recipient : recipients) {
            switch (recipient.getStatus()) {
                case CrmBroadcastRecipient.STATUS_SENT -> sent++;
                case CrmBroadcastRecipient.STATUS_DELIVERED -> delivered++;
                case CrmBroadcastRecipient.STATUS_READ -> read++;
                case CrmBroadcastRecipient.STATUS_FAILED -> failed++;
                case CrmBroadcastRecipient.STATUS_SKIPPED -> skipped++;
                default -> pending++;
            }
        }
        List<CrmDtos.BroadcastRecipientRow> rows = recipients.stream()
                .map(r -> new CrmDtos.BroadcastRecipientRow(
                        r.getId(),
                        r.getContactId(),
                        r.getPhoneE164(),
                        r.getStatus(),
                        r.getWaMessageId(),
                        r.getErrorMessage(),
                        r.getCreatedAt()))
                .toList();
        return new CrmDtos.BroadcastDetail(
                rowWithDerivedStatus(broadcast, pending), pending, sent, delivered, read, failed, skipped, rows);
    }

    private CrmDtos.BroadcastRow toRow(CrmBroadcast broadcast) {
        int pending = (int) recipientRepository.countByBroadcastIdAndStatus(
                broadcast.getId(), CrmBroadcastRecipient.STATUS_PENDING);
        return rowWithDerivedStatus(broadcast, pending);
    }

    /** A broadcast whose last pending recipient has settled is done, even before a re-read. */
    private CrmDtos.BroadcastRow rowWithDerivedStatus(CrmBroadcast broadcast, int pending) {
        String status = broadcast.getStatus();
        if (CrmBroadcast.STATUS_SENDING.equals(status) && pending == 0) {
            status = CrmBroadcast.STATUS_SENT;
        }
        return new CrmDtos.BroadcastRow(
                broadcast.getId(), broadcast.getName(), broadcast.getMode(), status,
                broadcast.getTotalCount(), broadcast.getCreatedAt());
    }

    private CrmBroadcast require(String businessId, String id) {
        return broadcastRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Broadcast not found"));
    }

    private static Map<String, Object> audienceJson(CrmDtos.BroadcastAudience audience) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", audience == null || isBlank(audience.type()) ? "all" : audience.type().trim());
        if (audience != null && !isBlank(audience.tag())) {
            map.put("tag", audience.tag().trim());
        }
        return map;
    }

    private List<String> readTags(CrmContact contact) {
        if (contact.getTagsJson() == null || contact.getTagsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(contact.getTagsJson(), new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("crm broadcast json serialization failed", ex);
        }
    }

    private static String normaliseMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case CrmBroadcast.MODE_TEMPLATE -> CrmBroadcast.MODE_TEMPLATE;
            case CrmBroadcast.MODE_FREE_FORM -> CrmBroadcast.MODE_FREE_FORM;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be free_form or template");
        };
    }

    private static String normaliseLanguage(String language) {
        return isBlank(language) ? "en_US" : language.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
