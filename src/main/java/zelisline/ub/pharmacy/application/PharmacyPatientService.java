package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
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
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.PatientHistory;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.PatientMatch;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.PatientPage;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.PatientRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.PatientResponse;
import zelisline.ub.pharmacy.domain.PharmacyPatient;
import zelisline.ub.pharmacy.repository.PharmacyPatientRepository;

/**
 * Patient profiles (ADR 0033, Phase 2). Health data: every read and every change is audited with
 * the actor and a count, never with the patient's name, phone or notes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PharmacyPatientService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PharmacyPatientRepository patientRepository;
    private final DispensingRecordQueryService recordQueryService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public PatientPage list(String businessId, String q, int page, int size, String viewerUserId) {
        String pattern = pattern(q);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        List<PatientResponse> out = patientRepository
                .search(businessId, pattern, PageRequest.of(safePage, safeSize)).stream()
                .map(PharmacyPatientService::toResponse)
                .toList();
        long total = patientRepository.countSearch(businessId, pattern);
        audit(businessId, viewerUserId, AuditEventTypes.PHARMACY_PATIENT_VIEWED, null, out.size());
        return new PatientPage(out, total, safePage, safeSize);
    }

    /** The profile with the patient's dispensing history. */
    @Transactional(readOnly = true)
    public PatientHistory history(String businessId, String patientId, String viewerUserId) {
        PharmacyPatient p = require(businessId, patientId);
        audit(businessId, viewerUserId, AuditEventTypes.PHARMACY_PATIENT_VIEWED, p.getId(), 1);
        return new PatientHistory(toResponse(p), recordQueryService.forPatient(businessId, p.getId(), viewerUserId));
    }

    /**
     * Patients with exactly this phone number, for the dispensing dialog at the till. An exact
     * match only, so the till cannot be used to browse the patient list.
     */
    @Transactional(readOnly = true)
    public List<PatientMatch> lookupByPhone(String businessId, String phone, String viewerUserId) {
        String normal = normalizePhone(phone);
        if (normal == null || normal.length() < 7) {
            return List.of();
        }
        List<PatientMatch> out = patientRepository
                .findByBusinessIdAndPhoneAndDeletedAtIsNull(businessId, normal).stream()
                .limit(5)
                .map(p -> new PatientMatch(p.getId(), p.getName(), p.getPhone(), p.getAllergies()))
                .toList();
        if (!out.isEmpty()) {
            audit(businessId, viewerUserId, AuditEventTypes.PHARMACY_PATIENT_VIEWED, null, out.size());
        }
        return out;
    }

    @Transactional
    public PatientResponse create(String businessId, PatientRequest req, String userId) {
        PharmacyPatient p = new PharmacyPatient();
        p.setBusinessId(businessId);
        p.setCreatedBy(userId);
        apply(p, req);
        patientRepository.save(p);
        audit(businessId, userId, AuditEventTypes.PHARMACY_PATIENT_SAVED, p.getId(), 1);
        return toResponse(p);
    }

    @Transactional
    public PatientResponse update(String businessId, String patientId, PatientRequest req, String userId) {
        PharmacyPatient p = require(businessId, patientId);
        apply(p, req);
        patientRepository.save(p);
        audit(businessId, userId, AuditEventTypes.PHARMACY_PATIENT_SAVED, p.getId(), 1);
        return toResponse(p);
    }

    /** Hides the profile. Dispensing records keep the name and phone that were typed on them. */
    @Transactional
    public void remove(String businessId, String patientId, String userId) {
        PharmacyPatient p = require(businessId, patientId);
        p.setDeletedAt(Instant.now());
        patientRepository.save(p);
        audit(businessId, userId, AuditEventTypes.PHARMACY_PATIENT_SAVED, p.getId(), 0);
    }

    private PharmacyPatient require(String businessId, String patientId) {
        return patientRepository.findByIdAndBusinessIdAndDeletedAtIsNull(patientId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"));
    }

    private static void apply(PharmacyPatient p, PatientRequest req) {
        p.setName(req.name().strip());
        p.setPhone(normalizePhone(req.phone()));
        p.setDateOfBirth(req.dateOfBirth());
        p.setAllergies(blankToNull(req.allergies()));
        p.setNotes(blankToNull(req.notes()));
        boolean consent = Boolean.TRUE.equals(req.reminderConsent());
        if (consent != p.isReminderConsent()) {
            p.setReminderConsent(consent);
            p.setReminderConsentAt(consent ? Instant.now() : null);
        }
    }

    static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\s\\-()]", "");
        return s.isEmpty() ? null : s;
    }

    private static String pattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        return "%" + q.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static PatientResponse toResponse(PharmacyPatient p) {
        return new PatientResponse(
                p.getId(), p.getName(), p.getPhone(), p.getDateOfBirth(), p.getAllergies(), p.getNotes(),
                p.isReminderConsent(), p.getReminderConsentAt(), p.getCreatedAt());
    }

    private void audit(String businessId, String userId, String type, String patientId, int count) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("patients", count);
            var builder = auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, type, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .newState(state);
            if (patientId != null) {
                builder.target("pharmacy_patient", patientId);
            }
            auditEventPublisher.publish(builder.build());
        } catch (Exception ex) {
            log.warn("Failed to audit patient access business={}: {}", businessId, ex.toString());
        }
    }
}
