package zelisline.ub.till.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.notifications.application.NotificationTemplateRenderer;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.till.api.dto.PublicTillAccessReviewResponse;
import zelisline.ub.till.api.dto.RegisterTillDeviceRequest;
import zelisline.ub.till.api.dto.TillDeviceResponse;
import zelisline.ub.till.domain.TillAccessRequest;
import zelisline.ub.till.repository.TillAccessRequestRepository;
import zelisline.ub.till.repository.TillDeviceRepository;

@Service
@RequiredArgsConstructor
public class TillAccessRequestService {

    private static final Logger log = LoggerFactory.getLogger(TillAccessRequestService.class);
    static final String APPROVE_PERMISSION = "business.manage_settings";
    static final Duration LINK_TTL = Duration.ofDays(7);
    static final Duration QUIET_PERIOD = Duration.ofHours(6);

    private final TillAccessRequestRepository tillAccessRequestRepository;
    private final TillDeviceRepository tillDeviceRepository;
    private final TillDeviceService tillDeviceService;
    private final TillAccessApprovalToken tillAccessApprovalToken;
    private final NotificationService notificationService;
    private final NotificationTemplateRenderer templateRenderer;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /**
     * Called after PIN is verified but the till is not trusted. Commits in its
     * own transaction so the surrounding login 403 can still roll back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPinDenied(User user, String branchId, String deviceKey, String userAgent) {
        if (user == null) {
            return;
        }
        String key;
        try {
            key = TillDeviceService.resolveDeviceKey(deviceKey, null);
        } catch (ResponseStatusException ex) {
            return;
        }
        String businessId = user.getBusinessId();
        String branch = branchId != null ? branchId.trim() : "";
        if (businessId == null || businessId.isBlank() || branch.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        TillAccessRequest row = tillAccessRequestRepository
                .findByBusinessIdAndBranchIdAndDeviceKey(businessId, branch, key)
                .orElse(null);

        if (row == null) {
            row = new TillAccessRequest();
            row.setBusinessId(businessId);
            row.setBranchId(branch);
            row.setDeviceKey(key);
            applyRequester(row, user, key, userAgent, now);
            row.setStatus(TillAccessRequest.STATUS_PENDING);
            tillAccessRequestRepository.save(row);
            notifyApprovers(row);
            return;
        }

        applyRequester(row, user, key, userAgent, now);

        if (TillAccessRequest.STATUS_PENDING.equals(row.getStatus())) {
            tillAccessRequestRepository.save(row);
            if (shouldNotifyAgain(row, now)) {
                notifyApprovers(row);
            }
            return;
        }

        if (TillAccessRequest.STATUS_APPROVED.equals(row.getStatus())
                && tillDeviceRepository.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
                        businessId, branch, key)) {
            tillAccessRequestRepository.save(row);
            return;
        }

        if (TillAccessRequest.STATUS_DISMISSED.equals(row.getStatus())
                && row.getResolvedAt() != null
                && row.getResolvedAt().isAfter(now.minus(QUIET_PERIOD))) {
            tillAccessRequestRepository.save(row);
            return;
        }

        row.setStatus(TillAccessRequest.STATUS_PENDING);
        row.setResolvedBy(null);
        row.setResolvedAt(null);
        tillAccessRequestRepository.save(row);
        notifyApprovers(row);
    }

    @Transactional(readOnly = true)
    public PublicTillAccessReviewResponse reviewByToken(String token) {
        return toReview(requireByToken(token));
    }

    @Transactional
    public PublicTillAccessReviewResponse approveByToken(String token, String label) {
        TillAccessRequest row = requireByToken(token);
        if (TillAccessRequest.STATUS_APPROVED.equals(row.getStatus())
                && tillDeviceRepository.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
                        row.getBusinessId(), row.getBranchId(), row.getDeviceKey())) {
            return toReview(row);
        }

        String actorId = resolveLinkActorUserId(row.getBusinessId());
        if (actorId == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No shop owner is available to register this till.");
        }
        String resolvedLabel = label != null && !label.isBlank()
                ? label.trim()
                : row.getSuggestedLabel();
        TillDeviceResponse registered = tillDeviceService.register(
                row.getBusinessId(),
                actorId,
                row.getBranchId(),
                new RegisterTillDeviceRequest(row.getBranchId(), row.getDeviceKey(), resolvedLabel, null),
                row.getDeviceKey());
        if (registered.label() != null && !registered.label().isBlank()) {
            row.setSuggestedLabel(registered.label());
        }
        row.setStatus(TillAccessRequest.STATUS_APPROVED);
        row.setResolvedBy(actorId);
        row.setResolvedAt(Instant.now());
        tillAccessRequestRepository.save(row);
        return toReview(row);
    }

    @Transactional
    public PublicTillAccessReviewResponse dismissByToken(String token) {
        TillAccessRequest row = requireByToken(token);
        if (TillAccessRequest.STATUS_APPROVED.equals(row.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This till is already trusted. Revoke it from Trusted tills if needed.");
        }
        if (TillAccessRequest.STATUS_DISMISSED.equals(row.getStatus())) {
            return toReview(row);
        }
        String actorId = resolveLinkActorUserId(row.getBusinessId());
        row.setStatus(TillAccessRequest.STATUS_DISMISSED);
        row.setResolvedBy(actorId);
        row.setResolvedAt(Instant.now());
        tillAccessRequestRepository.save(row);
        return toReview(row);
    }

    String publicReviewUrl(TillAccessRequest row) {
        Instant exp = Instant.now().plus(LINK_TTL);
        String token = tillAccessApprovalToken.issue(row.getId(), exp);
        String base = frontendBaseUrl == null || frontendBaseUrl.isBlank()
                ? "http://localhost:3000"
                : frontendBaseUrl.trim().replaceAll("/+$", "");
        return base + "/tills/review?token=" + token;
    }

    static String suggestedLabel(String cashierName, String deviceKey) {
        String first = firstName(cashierName);
        if (first != null) {
            String label = first + "'s till";
            return label.length() > 80 ? label.substring(0, 80) : label;
        }
        return TillDeviceService.resolveLabel(null, deviceKey);
    }

    static String deviceShortId(String deviceKey) {
        if (deviceKey == null || deviceKey.isBlank()) {
            return "";
        }
        String compact = deviceKey.replace("-", "");
        String shortId = compact.length() > 8 ? compact.substring(0, 8) : compact;
        return shortId.toLowerCase(Locale.ROOT);
    }

    private void applyRequester(
            TillAccessRequest row,
            User user,
            String deviceKey,
            String userAgent,
            Instant now
    ) {
        row.setRequestedByUserId(user.getId());
        row.setRequestedByName(trimTo(user.getName(), 160, "Cashier"));
        row.setRequestedByEmail(trimTo(user.getEmail(), 191, ""));
        row.setSuggestedLabel(suggestedLabel(user.getName(), deviceKey));
        row.setUserAgent(trimToNull(userAgent, 240));
        row.setLastSeenAt(now);
    }

    private boolean shouldNotifyAgain(TillAccessRequest row, Instant now) {
        Instant last = row.getNotifiedAt();
        return last == null || last.isBefore(now.minus(QUIET_PERIOD));
    }

    private void notifyApprovers(TillAccessRequest row) {
        String publicUrl = publicReviewUrl(row);
        String branchName = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(row.getBranchId(), row.getBusinessId())
                .map(Branch::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("the shop");
        String shopName = businessRepository.findById(row.getBusinessId())
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Kiosk");

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("cashierName", row.getRequestedByName());
        vars.put("cashierEmail", row.getRequestedByEmail());
        vars.put("branchName", branchName);
        vars.put("shopName", shopName);
        vars.put("tillLabel", row.getSuggestedLabel());
        vars.put("deviceShortId", deviceShortId(row.getDeviceKey()));
        vars.put("publicUrl", publicUrl);
        vars.put("actionUrl", publicUrl);

        NotificationTemplateRenderer.RenderedNotification rendered =
                templateRenderer.render(row.getBusinessId(), NotificationTypes.TILL_ACCESS_REQUESTED, vars);
        String payloadJson = buildPayload(rendered, vars);

        LinkedHashSet<String> approverIds = new LinkedHashSet<>(
                userRepository.findIdsWithPermission(row.getBusinessId(), APPROVE_PERMISSION));
        if (approverIds.isEmpty()) {
            for (String roleKey : List.of(IdentityService.OWNER_ROLE_KEY, "admin")) {
                for (User u : userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(row.getBusinessId(), roleKey)) {
                    approverIds.add(u.getId());
                }
            }
        }
        if (approverIds.isEmpty()) {
            log.warn("No owner/admin to notify for till access request {}", row.getId());
            return;
        }

        int generation = row.getNotifyCount() + 1;
        for (String userId : approverIds) {
            notificationService.tryInsertDedupeForUser(
                    row.getBusinessId(),
                    userId,
                    NotificationTypes.TILL_ACCESS_REQUESTED,
                    NotificationTypes.TILL_ACCESS_REQUESTED + ":" + row.getId() + ":" + userId + ":" + generation,
                    rendered.category(),
                    rendered.priority(),
                    payloadJson);
        }
        row.setNotifyCount(generation);
        row.setNotifiedAt(Instant.now());
        tillAccessRequestRepository.save(row);
    }

    private TillAccessRequest requireByToken(String token) {
        String requestId = tillAccessApprovalToken.verifyRequestId(token);
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This till link is invalid or expired.");
        }
        return tillAccessRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This till request is no longer available."));
    }

    private PublicTillAccessReviewResponse toReview(TillAccessRequest row) {
        boolean pending = TillAccessRequest.STATUS_PENDING.equals(row.getStatus());
        String shopName = businessRepository.findById(row.getBusinessId())
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Kiosk");
        String branchName = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(row.getBranchId(), row.getBusinessId())
                .map(Branch::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Branch");
        return new PublicTillAccessReviewResponse(
                row.getId(),
                row.getStatus(),
                shopName,
                branchName,
                row.getRequestedByName(),
                row.getRequestedByEmail(),
                row.getSuggestedLabel(),
                deviceShortId(row.getDeviceKey()),
                row.getUserAgent(),
                row.getLastSeenAt(),
                row.getCreatedAt(),
                pending);
    }

    private String resolveLinkActorUserId(String businessId) {
        List<String> approverIds = userRepository.findIdsWithPermission(businessId, APPROVE_PERMISSION);
        if (!approverIds.isEmpty()) {
            return approverIds.get(0);
        }
        return userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, IdentityService.OWNER_ROLE_KEY)
                .stream()
                .map(User::getId)
                .findFirst()
                .or(() -> userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, "admin")
                        .stream()
                        .map(User::getId)
                        .findFirst())
                .orElse(null);
    }

    private String buildPayload(
            NotificationTemplateRenderer.RenderedNotification rendered,
            Map<String, String> variables
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(variables);
        payload.put("title", rendered.title());
        payload.put("body", rendered.body());
        payload.put("actionUrl", rendered.actionUrl() != null && !rendered.actionUrl().isBlank()
                ? rendered.actionUrl()
                : variables.getOrDefault("publicUrl", "/tills"));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize till access notification payload", e);
            return "{}";
        }
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String first = name.trim().split("\\s+")[0];
        return first.length() >= 2 ? first : null;
    }

    private static String trimTo(String raw, int max, String fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String trimToNull(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        return value.length() > max ? value.substring(0, max) : value;
    }
}
