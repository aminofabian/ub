package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
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
    /** A household on one phone; more than this and the till is told to narrow it down. */
    private static final int MAX_LOOKUP_MATCHES = 12;
    private static final int NUMBER_ATTEMPTS = 4;
    private static final java.util.regex.Pattern PATIENT_NO =
            java.util.regex.Pattern.compile("^[Pp]\\s*[-#]?\\s*0*(\\d{1,12})$");

    private final PharmacyPatientRepository patientRepository;
    private final DispensingRecordQueryService recordQueryService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public PatientPage list(String businessId, String q, int page, int size, String viewerUserId) {
        String pattern = pattern(q);
        long patientNo = patientNoIn(q);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        List<PatientResponse> out = patientRepository
                .search(businessId, pattern, patientNo, PageRequest.of(safePage, safeSize)).stream()
                .map(PharmacyPatientService::toResponse)
                .toList();
        long total = patientRepository.countSearch(businessId, pattern, patientNo);
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
    /**
     * The till's lookup: a whole phone number, or a patient number typed as "P-412". A phone can
     * belong to several people, so every profile on it comes back, in name order.
     */
    @Transactional(readOnly = true)
    public List<PatientMatch> lookupByPhone(String businessId, String phone, String viewerUserId) {
        List<PharmacyPatient> found;
        long patientNo = patientNoIn(phone);
        if (patientNo > 0 && phone != null && PATIENT_NO.matcher(phone.strip()).matches()) {
            found = patientRepository
                    .findByBusinessIdAndPatientNoAndDeletedAtIsNull(businessId, patientNo)
                    .map(List::of)
                    .orElse(List.of());
        } else {
            String normal = normalizePhone(phone);
            if (normal == null || normal.length() < 7) {
                return List.of();
            }
            found = patientRepository
                    .findByBusinessIdAndPhoneAndDeletedAtIsNullOrderByNameAscIdAsc(businessId, normal);
        }
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        List<PatientMatch> out = found.stream()
                .limit(MAX_LOOKUP_MATCHES)
                .map(p -> new PatientMatch(
                        p.getId(), p.getPatientNo(), p.getName(), p.getPhone(),
                        ageYears(p.getDateOfBirth(), today), p.getAllergies()))
                .toList();
        if (!out.isEmpty()) {
            audit(businessId, viewerUserId, AuditEventTypes.PHARMACY_PATIENT_VIEWED, null, out.size());
        }
        return out;
    }

    /**
     * Reception's search (ADR 0034, decision 10): by name, phone or patient number. Returns name,
     * number, phone and age only; never allergies or notes. Every search is audited.
     */
    @Transactional(readOnly = true)
    public List<PatientMatch> searchForReception(String businessId, String q, String viewerUserId) {
        if (q == null || q.strip().length() < 2) {
            return List.of();
        }
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        List<PatientMatch> out = patientRepository
                .search(businessId, pattern(q), patientNoIn(q), PageRequest.of(0, 20)).stream()
                .map(p -> new PatientMatch(
                        p.getId(), p.getPatientNo(), p.getName(), p.getPhone(),
                        ageYears(p.getDateOfBirth(), today), null))
                .toList();
        audit(businessId, viewerUserId, AuditEventTypes.PHARMACY_PATIENT_VIEWED, null, out.size());
        return out;
    }

    /**
     * Not one transaction: the number is "highest so far plus one", and two tills adding a patient
     * at the same moment would pick the same one. The unique key refuses the second; it then tries
     * again with a fresh number, each attempt in its own transaction.
     */
    public PatientResponse create(String businessId, PatientRequest req, String userId) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        org.springframework.dao.DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < NUMBER_ATTEMPTS; attempt++) {
            try {
                PatientResponse saved = tx.execute(status -> {
                    PharmacyPatient p = new PharmacyPatient();
                    p.setBusinessId(businessId);
                    p.setCreatedBy(userId);
                    p.setPatientNo(patientRepository.maxPatientNo(businessId) + 1);
                    apply(p, req);
                    patientRepository.saveAndFlush(p);
                    return toResponse(p);
                });
                audit(businessId, userId, AuditEventTypes.PHARMACY_PATIENT_SAVED, saved.id(), 1);
                return saved;
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                last = ex;
            }
        }
        log.warn("Could not number a new patient business={} after {} attempts: {}",
                businessId, NUMBER_ATTEMPTS, String.valueOf(last));
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Could not save the patient just now. Try again.");
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

    /** The number in "P-412", "p 0412" or "412"; -1 when {@code q} is not a patient number. */
    public static long patientNoIn(String q) {
        if (q == null) {
            return -1;
        }
        String s = q.strip();
        var m = PATIENT_NO.matcher(s);
        if (m.matches()) {
            return Long.parseLong(m.group(1));
        }
        // Bare digits short enough not to be a phone number.
        if (s.matches("\\d{1,6}")) {
            return Long.parseLong(s);
        }
        return -1;
    }

    public static Integer ageYears(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null || dateOfBirth.isAfter(today)) {
            return null;
        }
        return java.time.Period.between(dateOfBirth, today).getYears();
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
                p.getId(), p.getPatientNo(), p.getName(), p.getPhone(), p.getDateOfBirth(), p.getAllergies(),
                p.getNotes(),
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
