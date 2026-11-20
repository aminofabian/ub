package zelisline.ub.pharmacy.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.api.dto.DispensingApprovalResponse;
import zelisline.ub.pharmacy.api.dto.DispensingApproverResponse;
import zelisline.ub.pharmacy.domain.DispensingApproval;
import zelisline.ub.pharmacy.domain.DispensingApprovalFailure;
import zelisline.ub.pharmacy.domain.PharmacyStaffCredential;
import zelisline.ub.pharmacy.repository.DispensingApprovalFailureRepository;
import zelisline.ub.pharmacy.repository.DispensingApprovalRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffCredentialRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffQueryRepository;

/**
 * Lets a pharmacist approve a cashier's sale of gated medicines by entering their till PIN.
 *
 * <p>The PIN is checked here and never stored or forwarded: the sale request later carries only
 * the approval id, so nothing secret sits in an offline sale queue. An approval is bound to the
 * cashier who asked for it and to the medicines shown to the pharmacist, and can be used once.</p>
 *
 * <p>Deliberately not transactional as a whole: a wrong-PIN row must be committed even though
 * the call then fails, or guessing would never be counted.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispensingApprovalService {

    /**
     * Minutes, not hours: an approval that outlives the sale it was given for is a standing
     * permission. The cost is that a sale queued offline right after approval may need approving
     * again when it replays.
     */
    static final Duration APPROVAL_TTL = Duration.ofMinutes(15);
    static final Duration THROTTLE_WINDOW = Duration.ofMinutes(10);
    static final int MAX_FAILURES_PER_APPROVER = 5;
    static final int MAX_FAILURES_PER_REQUESTER = 10;
    /** Caps slow guessing: 5 per 10 minutes alone would allow hundreds of tries a day. */
    static final Duration DAILY_WINDOW = Duration.ofHours(24);
    static final int MAX_FAILURES_PER_APPROVER_PER_DAY = 15;
    private static final int MAX_ITEMS = 40;

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final DispensingApprovalRepository approvalRepository;
    private final DispensingApprovalFailureRepository failureRepository;
    private final PharmacyStaffQueryRepository staffQueryRepository;
    private final PharmacyStaffCredentialRepository credentialRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /** Staff who may approve, for the till's "who is approving?" list. Names and titles only. */
    public List<DispensingApproverResponse> approvers(String businessId) {
        List<User> users = staffQueryRepository.findActiveUsersWithPermission(
                businessId, DispensingGate.PERMISSION_DISPENSE);
        Set<String> ids = new LinkedHashSet<>();
        for (User u : users) {
            ids.add(u.getId());
        }
        Map<String, PharmacyStaffCredential> creds = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (PharmacyStaffCredential c : credentialRepository.findByBusinessIdAndUserIdIn(businessId, ids)) {
                creds.put(c.getUserId(), c);
            }
        }
        List<DispensingApproverResponse> out = new ArrayList<>();
        for (User u : users) {
            PharmacyStaffCredential c = creds.get(u.getId());
            out.add(new DispensingApproverResponse(
                    u.getId(),
                    u.getName(),
                    c == null ? null : c.getTitle(),
                    u.getPinHash() != null && !u.getPinHash().isBlank()));
        }
        return out;
    }

    public DispensingApprovalResponse approve(
            String businessId,
            String requesterUserId,
            String approverUserId,
            String pin,
            java.util.Map<String, java.math.BigDecimal> quantitiesByItemId
    ) {
        Set<String> items = new LinkedHashSet<>();
        if (quantitiesByItemId != null) {
            for (var e : quantitiesByItemId.entrySet()) {
                String id = e.getKey();
                java.math.BigDecimal q = e.getValue();
                if (id == null || id.isBlank() || id.contains(",") || id.contains("=")
                        || q == null || q.signum() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Say which medicines are being approved, and how many of each");
                }
                items.add(DispensingGate.approvalToken(id.strip(), q));
            }
        }
        if (items.isEmpty() || items.size() > MAX_ITEMS || String.join(",", items).length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say which medicines are being approved, and how many of each");
        }
        Instant since = Instant.now().minus(THROTTLE_WINDOW);
        if (failureRepository.countByBusinessIdAndApproverUserIdAndCreatedAtAfter(businessId, approverUserId, since)
                        >= MAX_FAILURES_PER_APPROVER
                || failureRepository.countByBusinessIdAndApproverUserIdAndCreatedAtAfter(
                                businessId, approverUserId, Instant.now().minus(DAILY_WINDOW))
                        >= MAX_FAILURES_PER_APPROVER_PER_DAY
                || failureRepository.countByBusinessIdAndRequestedByAndCreatedAtAfter(businessId, requesterUserId, since)
                        >= MAX_FAILURES_PER_REQUESTER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong PINs. Wait a few minutes and try again.");
        }
        User approver = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(approverUserId, businessId)
                .orElse(null);
        boolean eligible = approver != null
                && UserStatus.ACTIVE.wire().equals(approver.getStatus())
                && approver.getRoleId() != null
                && permissionRepository.findPermissionKeysByRoleId(approver.getRoleId())
                        .contains(DispensingGate.PERMISSION_DISPENSE);
        if (!eligible) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That person cannot approve dispensing");
        }
        String raw = pin == null ? "" : pin.strip();
        boolean ok = !raw.isEmpty()
                && approver.getPinHash() != null
                && !approver.getPinHash().isBlank()
                && passwordEncoder.matches(businessId + ":" + raw, approver.getPinHash());
        if (!ok) {
            DispensingApprovalFailure f = new DispensingApprovalFailure();
            f.setBusinessId(businessId);
            f.setApproverUserId(approverUserId);
            f.setRequestedBy(requesterUserId);
            failureRepository.save(f);
            audit(businessId, requesterUserId, approverUserId, AuditEventTypes.PHARMACY_APPROVAL_FAILED,
                    AuditEventSeverity.WARN, items.size());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong PIN");
        }
        approvalRepository.expireOpenForRequester(businessId, requesterUserId, Instant.now());
        DispensingApproval a = new DispensingApproval();
        a.setBusinessId(businessId);
        a.setApproverUserId(approverUserId);
        a.setRequestedBy(requesterUserId);
        a.setItemIds(String.join(",", items));
        a.setExpiresAt(Instant.now().plus(APPROVAL_TTL));
        approvalRepository.save(a);
        audit(businessId, requesterUserId, approverUserId, AuditEventTypes.PHARMACY_APPROVAL_GRANTED,
                AuditEventSeverity.INFO, items.size());
        return new DispensingApprovalResponse(a.getId(), approver.getId(), approver.getName(), a.getExpiresAt());
    }

    /** Who asked, whose PIN was tried, how many medicines. Never the PIN or the medicine names. */
    private void audit(
            String businessId,
            String requesterUserId,
            String approverUserId,
            String eventType,
            AuditEventSeverity severity,
            int medicineCount
    ) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("approverUserId", approverUserId);
            state.put("medicines", medicineCount);
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, eventType, severity)
                    .businessId(businessId)
                    .actor(requesterUserId, AuditEventActorType.USER)
                    .target("user", approverUserId)
                    .source("pharmacy")
                    .newState(state)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit {} business={}: {}", eventType, businessId, ex.toString());
        }
    }
}
