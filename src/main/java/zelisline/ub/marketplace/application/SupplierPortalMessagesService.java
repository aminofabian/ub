package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalMessageRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalMessageRow;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.SupplierPortalMessage;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.SupplierPortalMessageRepository;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalMessagesService {

    private final SupplierPortalMessageRepository messageRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final BusinessRepository businessRepository;
    private final ContactMessageRepository contactMessageRepository;

    @Transactional(readOnly = true)
    public List<SupplierPortalMessageRow> listForSupplier(String marketplaceSupplierId) {
        return messageRepository.findByMarketplaceSupplierIdOrderByCreatedAtDesc(marketplaceSupplierId)
                .stream()
                .map(this::toRow)
                .toList();
    }

    @Transactional
    public SupplierPortalMessageRow sendFromSupplier(
            String marketplaceSupplierId,
            String authorName,
            CreateSupplierPortalMessageRequest request
    ) {
        BusinessSupplierConnection link = connectionRepository
                .findByMarketplaceSupplierIdAndLocalSupplierId(
                        marketplaceSupplierId, request.localSupplierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found"));
        if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(link.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found");
        }
        String body = request.body().trim();
        if (body.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is too short");
        }

        ContactMessage shopInbox = new ContactMessage();
        shopInbox.setScope(ContactMessageScope.TENANT);
        shopInbox.setBusinessId(link.getBusinessId());
        shopInbox.setName(trimTo(authorName == null || authorName.isBlank() ? "Supplier" : authorName, 120));
        shopInbox.setEmail("supplier-portal@" + marketplaceSupplierId.replace("-", "").substring(0, 8) + ".local");
        shopInbox.setBody(trimTo("[Supplier portal message]\n" + body, 4000));
        shopInbox.setStatus(ContactMessageStatus.UNREAD);
        shopInbox.setSourcePath("/supplier-portal/messages");
        ContactMessage savedInbox = contactMessageRepository.save(shopInbox);

        SupplierPortalMessage msg = new SupplierPortalMessage();
        msg.setMarketplaceSupplierId(marketplaceSupplierId);
        msg.setBusinessId(link.getBusinessId());
        msg.setLocalSupplierId(link.getLocalSupplierId());
        msg.setDirection(SupplierPortalMessage.FROM_SUPPLIER);
        msg.setAuthorName(trimTo(authorName == null || authorName.isBlank() ? "Supplier" : authorName, 120));
        msg.setBody(trimTo(body, 4000));
        msg.setContactMessageId(savedInbox.getId());
        return toRow(messageRepository.save(msg));
    }

    @Transactional
    public void recordFromShop(
            String marketplaceSupplierId,
            String businessId,
            String localSupplierId,
            String authorName,
            String body,
            String contactMessageId
    ) {
        SupplierPortalMessage msg = new SupplierPortalMessage();
        msg.setMarketplaceSupplierId(marketplaceSupplierId);
        msg.setBusinessId(businessId);
        msg.setLocalSupplierId(localSupplierId);
        msg.setDirection(SupplierPortalMessage.FROM_SHOP);
        msg.setAuthorName(trimTo(authorName, 120));
        msg.setBody(trimTo(body, 4000));
        msg.setContactMessageId(contactMessageId);
        messageRepository.save(msg);
    }

    @Transactional
    public void markRead(String marketplaceSupplierId, String messageId) {
        SupplierPortalMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        if (!marketplaceSupplierId.equals(msg.getMarketplaceSupplierId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found");
        }
        if (msg.getReadAt() == null) {
            msg.setReadAt(java.time.Instant.now());
            messageRepository.save(msg);
        }
    }

    private SupplierPortalMessageRow toRow(SupplierPortalMessage msg) {
        String shopName = businessRepository.findById(msg.getBusinessId())
                .map(b -> b.getName())
                .orElse("Shop");
        return new SupplierPortalMessageRow(
                msg.getId(),
                msg.getBusinessId(),
                shopName,
                msg.getLocalSupplierId(),
                msg.getDirection(),
                msg.getAuthorName(),
                msg.getBody(),
                msg.getCreatedAt(),
                msg.getReadAt());
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return "";
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
