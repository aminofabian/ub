package zelisline.ub.crm.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmContact;
import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.repository.CrmContactRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.integrations.whatsapp.application.ChannelResolver;

/**
 * Ensures a web order has a CRM identity, so an online order shows up in the WhatsApp
 * inbox next to chat threads and can be the subject of a {@code SHOP_EVENT} automation
 * (scope P2 #13).
 *
 * <p>Keyed by phone: the same customer's orders and messages share one contact and one
 * open conversation. This is the CRM's own record — it never owns customer identity
 * (SCOPE §6.3).
 */
@Service
@RequiredArgsConstructor
public class CrmOrderLinkingService {

    private static final int NAME_MAX = 160;

    private final CrmContactRepository contactRepository;
    private final CrmConversationRepository conversationRepository;
    private final ChannelResolver channelResolver;

    /** Resolved contact + conversation for an order's customer. */
    public record Handle(String contactId, String conversationId) {
    }

    /**
     * Find or create the contact + open conversation for a phone. Idempotent: a repeat
     * order from the same number reuses both.
     *
     * @return the handle, or {@code null} when the phone cannot be normalised
     */
    @Transactional
    public Handle ensureForOrder(String businessId, String rawPhone, String name) {
        String phone = toE164Digits(rawPhone);
        if (businessId == null || businessId.isBlank() || phone == null) {
            return null;
        }

        CrmContact contact = contactRepository
                .findByBusinessIdAndPhoneE164(businessId, phone)
                .orElseGet(() -> {
                    CrmContact created = new CrmContact();
                    created.setBusinessId(businessId);
                    created.setPhoneE164(phone);
                    created.setName(nameFor(name));
                    return contactRepository.save(created);
                });
        // Backfill a name if the contact was created without one (e.g. an order before
        // the customer ever messaged).
        if ((contact.getName() == null || contact.getName().isBlank()) && nameFor(name) != null) {
            contact.setName(nameFor(name));
            contact.setUpdatedAt(java.time.Instant.now());
            contactRepository.save(contact);
        }

        CrmConversation conversation = conversationRepository
                .findFirstByBusinessIdAndContactIdAndStatusOrderByUpdatedAtDesc(
                        businessId, contact.getId(), CrmConversation.STATUS_OPEN)
                .orElseGet(() -> {
                    CrmConversation created = new CrmConversation();
                    created.setBusinessId(businessId);
                    created.setContactId(contact.getId());
                    created.setStatus(CrmConversation.STATUS_OPEN);
                    channelResolver.activePhoneNumberIdForBusiness(businessId)
                            .ifPresent(created::setPhoneNumberId);
                    return conversationRepository.save(created);
                });

        return new Handle(contact.getId(), conversation.getId());
    }

    private static String nameFor(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > NAME_MAX ? trimmed.substring(0, NAME_MAX) : trimmed;
    }

    /** Kenyan MSISDN as digits only, country-coded ({@code 2547…}); null when implausible. */
    static String toE164Digits(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.startsWith("0")) {
            digits = "254" + digits.substring(1);
        }
        if (!digits.startsWith("254")) {
            digits = "254" + digits;
        }
        return digits.length() >= 12 ? digits : null;
    }
}
