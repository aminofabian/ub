package zelisline.ub.support.api;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.media.AttachmentStore;
import zelisline.ub.platform.media.CloudinarySignatureService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.web.MediaController;
import zelisline.ub.platform.realtime.RealtimeScopes;
import zelisline.ub.platform.realtime.RealtimeTicketController;
import zelisline.ub.platform.realtime.RealtimeTicketService;
import zelisline.ub.platform.realtime.TicketRecord;
import zelisline.ub.support.api.dto.GuestThreadDto;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.support.domain.SupportConversation;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Guest chat for anonymous visitors: kiosk.ke guests talking to the platform
 * team ({@code VISITOR}) and storefront buyers talking to a tenant's staff
 * ({@code STOREFRONT}).
 *
 * <p>Guests authenticate with two headers the browser holds in localStorage:
 * {@code X-Guest-Id} (the visitor's UUID) and {@code X-Guest-Token} (the
 * thread secret minted at creation). Everything here is {@code permitAll} but
 * IP rate-limited by {@code PublicSupportRateLimitFilter}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/support")
@RequiredArgsConstructor
public class PublicSupportController {

    public static final String HEADER_GUEST_ID = "X-Guest-Id";
    public static final String HEADER_GUEST_TOKEN = "X-Guest-Token";
    public static final String HEADER_GUEST_PHONE = "X-Guest-Phone";
    private static final String SUPPORT_FOLDER_PREFIX = "ub/support/";
    private static final String PROVIDER_R2 = "r2";
    private static final String PROVIDER_CLOUDINARY = "cloudinary";
    private static final String RESOURCE_RAW = "raw";

    private final SupportService supportService;
    private final BusinessRepository businessRepository;
    private final RealtimeTicketService ticketService;
    private final RealtimeTicketController tenantTicketController;
    private final CloudinarySignatureService cloudinarySignatureService;
    private final ObjectProvider<AttachmentStore> attachmentStore;

    @PostMapping("/threads")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestThreadDto startOrResume(
            @Valid @RequestBody StartGuestThreadRequest body,
            @RequestHeader(value = HEADER_GUEST_TOKEN, required = false) String token,
            @RequestHeader(value = HEADER_GUEST_PHONE, required = false) String guestPhone
    ) {
        String businessId = resolveBusinessId(body.type(), body.businessSlug());
        return supportService.guestStartOrResume(
                body.type(), businessId, body.guestId(), body.guestName(), guestPhone, body.body(), token);
    }

    @GetMapping("/threads/me")
    public GuestThreadDto resume(
            @RequestParam String type,
            @RequestParam(required = false) String businessSlug,
            @RequestParam String guestId,
            @RequestHeader(value = HEADER_GUEST_TOKEN, required = false) String token,
            @RequestHeader(value = HEADER_GUEST_PHONE, required = false) String guestPhone
    ) {
        String businessId = resolveBusinessId(type, businessSlug);
        return supportService.guestResume(type, businessId, guestId, guestPhone, token);
    }

    @PostMapping("/threads/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageDto send(
            @PathVariable String id,
            @Valid @RequestBody SendSupportMessageRequest body,
            @RequestHeader(HEADER_GUEST_ID) String guestId,
            @RequestHeader(HEADER_GUEST_TOKEN) String token
    ) {
        return supportService.sendGuestMessage(id, guestId, token, body);
    }

    /**
     * Upload handshake for a guest thread (auto = images + docs). With uploads on R2 the answer is
     * {@code provider=r2} and the client posts the file to {@link #uploadAttachment}.
     */
    @PostMapping("/threads/{id}/cloudinary-signature")
    public Map<String, Object> cloudinarySignature(
            @PathVariable String id,
            @RequestHeader(HEADER_GUEST_ID) String guestId,
            @RequestHeader(HEADER_GUEST_TOKEN) String token
    ) {
        supportService.requireGuestThreadAccess(id, guestId, token);
        String folder = supportFolder(id);
        if (attachmentsOnR2()) {
            return Map.of(
                    "provider", PROVIDER_R2,
                    "folder", folder,
                    "resourceType", CloudinarySignatureService.RESOURCE_AUTO);
        }
        if (!cloudinarySignatureService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloudinary is not configured on this server");
        }
        var result = cloudinarySignatureService.signUpload(folder, CloudinarySignatureService.RESOURCE_AUTO);
        return Map.of(
                "provider", PROVIDER_CLOUDINARY,
                "cloudName", result.cloudName(),
                "apiKey", result.apiKey(),
                "timestamp", result.timestamp(),
                "signature", result.signature(),
                "folder", result.folder() == null ? "" : result.folder(),
                "resourceType", result.resourceType()
        );
    }

    /** Stores a guest's attachment in R2 under the thread's folder; response mirrors Cloudinary's upload JSON. */
    @PostMapping(value = "/threads/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadAttachment(
            @PathVariable String id,
            @RequestHeader(HEADER_GUEST_ID) String guestId,
            @RequestHeader(HEADER_GUEST_TOKEN) String token,
            @RequestPart("file") MultipartFile file
    ) {
        supportService.requireGuestThreadAccess(id, guestId, token);
        if (!attachmentsOnR2()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Attachment storage is not configured");
        }
        CloudinaryUploadResult result = attachmentStore.getObject()
                .uploadAttachment(readBytes(file), file.getOriginalFilename(), supportFolder(id));
        return MediaController.toCloudinaryShape(result, RESOURCE_RAW);
    }

    private boolean attachmentsOnR2() {
        AttachmentStore store = attachmentStore.getIfAvailable();
        return store != null && store.isActive();
    }

    private static String supportFolder(String threadId) {
        return SUPPORT_FOLDER_PREFIX + threadId.trim();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }

    @PostMapping("/threads/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @PathVariable String id,
            @RequestHeader(HEADER_GUEST_ID) String guestId,
            @RequestHeader(HEADER_GUEST_TOKEN) String token
    ) {
        supportService.markGuestRead(id, guestId, token);
    }

    /**
     * Mint a WebSocket ticket for a guest session. The guest's socket only ever
     * receives its own channel ({@code support.guest:<guestId>}).
     */
    @PostMapping("/realtime/tickets")
    public ResponseEntity<Map<String, Object>> mintTicket(
            @RequestHeader(HEADER_GUEST_ID) String guestId,
            @RequestHeader(HEADER_GUEST_TOKEN) String token,
            HttpServletRequest request
    ) {
        if (guestId.isBlank() || !supportService.guestHasValidToken(guestId, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid guest credentials");
        }
        String channel = SupportService.GUEST_CHANNEL_PREFIX + guestId.trim();
        TicketRecord record = ticketService.mint(
                guestId.trim(), RealtimeScopes.GUEST, null, Set.of(channel));
        return ResponseEntity.ok(Map.of(
                "ticket", record.ticket(),
                "expiresAt", record.expiresAt().toEpochMilli(),
                "wsUrl", tenantTicketController.resolveWebSocketUrl(request)
        ));
    }

    private String resolveBusinessId(String type, String businessSlug) {
        if (SupportConversation.TYPE_VISITOR.equals(type)) {
            return SupportConversation.PLATFORM_BUSINESS;
        }
        if (!SupportConversation.TYPE_STOREFRONT.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "type must be VISITOR or STOREFRONT");
        }
        if (businessSlug == null || businessSlug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "businessSlug is required for STOREFRONT chat");
        }
        return businessRepository.findBySlugAndDeletedAtIsNull(businessSlug.trim())
                .map(Business::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Storefront not found"));
    }

    /** Start (or resume) a guest thread. {@code token} is a header, not a body field. */
    public record StartGuestThreadRequest(
            @NotBlank String type,
            String businessSlug,
            @NotBlank String guestId,
            String guestName,
            String body
    ) {}
}
