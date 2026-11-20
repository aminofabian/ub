package zelisline.ub.pharmacy.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.api.dto.PharmacyProfileResponse;
import zelisline.ub.pharmacy.api.dto.UpdatePharmacyProfileRequest;
import zelisline.ub.pharmacy.api.dto.UpdateStaffCredentialRequest;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.domain.PharmacyStaffCredential;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffCredentialRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffQueryRepository;

/**
 * The pharmacy profile: premises licence details as the shop entered them, the superintending
 * pharmacist, whether controlled medicines may be sold through the till, and the credentials of
 * the staff who may dispense. Nothing here is verified with a regulator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PharmacyProfileService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");

    private final PharmacyProfileRepository profileRepository;
    private final PharmacyStaffCredentialRepository credentialRepository;
    private final PharmacyStaffQueryRepository staffQueryRepository;
    private final UserRepository userRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public PharmacyProfileResponse get(String businessId) {
        PharmacyProfile p = profileRepository.findById(businessId).orElse(null);
        return toResponse(businessId, p);
    }

    @Transactional
    public PharmacyProfileResponse update(String businessId, UpdatePharmacyProfileRequest req, String userId) {
        PharmacyProfile p = profileRepository.findById(businessId).orElse(null);
        boolean controlledBefore = p != null && p.isControlledSalesEnabled();
        if (p == null) {
            p = new PharmacyProfile();
            p.setBusinessId(businessId);
        }
        p.setPremisesLicenceNo(blankToNull(req.premisesLicenceNo()));
        if (req.licenceExpiresOn() == null || !req.licenceExpiresOn().equals(p.getLicenceExpiresOn())) {
            // A new expiry date starts the reminder cycle again.
            p.setLicenceReminderSentFor(null);
            p.setLicenceReminderStage(0);
        }
        p.setLicenceExpiresOn(req.licenceExpiresOn());
        String superintendent = blankToNull(req.superintendentUserId());
        if (superintendent != null) {
            userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(superintendent, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member not found"));
        }
        p.setSuperintendentUserId(superintendent);
        if (req.controlledSalesEnabled() != null) {
            p.setControlledSalesEnabled(req.controlledSalesEnabled());
        }
        if (req.shortDatedDays() != null) {
            p.setShortDatedDays(req.shortDatedDays());
        }
        if (req.secondCheckEnabled() != null) {
            p.setSecondCheckEnabled(req.secondCheckEnabled());
        }
        if (req.orderLeadDays() != null) {
            p.setOrderLeadDays(req.orderLeadDays());
        }
        if (req.orderCoverDays() != null) {
            p.setOrderCoverDays(req.orderCoverDays());
        }
        profileRepository.save(p);
        audit(businessId, userId, controlledBefore, p.isControlledSalesEnabled());
        return toResponse(businessId, p);
    }

    @Transactional
    public PharmacyProfileResponse updateStaffCredential(
            String businessId,
            String staffUserId,
            UpdateStaffCredentialRequest req
    ) {
        userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(staffUserId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        PharmacyStaffCredential c = credentialRepository.findByUserIdAndBusinessId(staffUserId, businessId)
                .orElse(null);
        if (c == null) {
            c = new PharmacyStaffCredential();
            c.setUserId(staffUserId);
            c.setBusinessId(businessId);
        }
        c.setTitle(blankToNull(req.title()));
        c.setRegistrationNo(blankToNull(req.registrationNo()));
        credentialRepository.save(c);
        return get(businessId);
    }

    /** The shop's short-dated window in days (default 90) for the expiry card. */
    @Transactional(readOnly = true)
    public int shortDatedDays(String businessId) {
        return profileRepository.findById(businessId).map(PharmacyProfile::getShortDatedDays).orElse(90);
    }

    private PharmacyProfileResponse toResponse(String businessId, PharmacyProfile p) {
        Map<String, PharmacyStaffCredential> creds = new LinkedHashMap<>();
        for (PharmacyStaffCredential c : credentialRepository.findByBusinessId(businessId)) {
            creds.put(c.getUserId(), c);
        }
        List<PharmacyProfileResponse.Staff> staff = new ArrayList<>();
        for (User u : staffQueryRepository.findActiveUsersWithPermission(businessId, DispensingGate.PERMISSION_DISPENSE)) {
            PharmacyStaffCredential c = creds.get(u.getId());
            staff.add(new PharmacyProfileResponse.Staff(
                    u.getId(),
                    u.getName(),
                    c == null ? null : c.getTitle(),
                    c == null ? null : c.getRegistrationNo(),
                    u.getPinHash() != null && !u.getPinHash().isBlank()));
        }
        if (p == null) {
            return new PharmacyProfileResponse(null, null, null, null, null, false, 90, staff, false);
        }
        Long daysLeft = p.getLicenceExpiresOn() == null
                ? null
                : ChronoUnit.DAYS.between(LocalDate.now(SHOP_ZONE), p.getLicenceExpiresOn());
        String superintendentName = p.getSuperintendentUserId() == null
                ? null
                : userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(p.getSuperintendentUserId(), businessId)
                        .map(User::getName)
                        .orElse(null);
        return new PharmacyProfileResponse(
                p.getPremisesLicenceNo(),
                p.getLicenceExpiresOn(),
                daysLeft,
                p.getSuperintendentUserId(),
                superintendentName,
                p.isControlledSalesEnabled(),
                p.getShortDatedDays(),
                staff,
                p.isSecondCheckEnabled());
    }

    private void audit(String businessId, String userId, boolean controlledBefore, boolean controlledAfter) {
        try {
            Map<String, Object> oldState = new LinkedHashMap<>();
            oldState.put("controlledSalesEnabled", controlledBefore);
            Map<String, Object> newState = new LinkedHashMap<>();
            newState.put("controlledSalesEnabled", controlledAfter);
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SYSTEM, AuditEventTypes.PHARMACY_PROFILE_UPDATED,
                            controlledBefore != controlledAfter ? AuditEventSeverity.WARN : AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .target("business", businessId)
                    .source("pharmacy")
                    .oldState(oldState)
                    .newState(newState)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit pharmacy profile update business={}: {}", businessId, ex.toString());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
