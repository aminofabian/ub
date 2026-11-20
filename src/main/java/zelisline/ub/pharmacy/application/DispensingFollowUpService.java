package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.Owing;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.RefillDue;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.domain.PharmacyPatient;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacyPatientRepository;

/**
 * What a pharmacy still owes its patients (ADR 0033, Phase 2): prescriptions supplied in part,
 * and supplies that are about to run out. Both lists name patients, so both reads are audited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispensingFollowUpService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final int MAX_ROWS = 500;
    /** A balance older than this is no longer offered; the prescription has lapsed. */
    private static final int OWING_WINDOW_DAYS = 180;
    private static final int REFILL_DAYS_BACK = 14;
    private static final int REFILL_DAYS_AHEAD = 7;

    private final DispensingRecordRepository recordRepository;
    private final DispensingRecordLineRepository lineRepository;
    private final PharmacyPatientRepository patientRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /** Prescriptions supplied in part whose balance has not been supplied since. */
    @Transactional(readOnly = true)
    public List<Owing> owings(String businessId, String onlyBranchId, String viewerUserId) {
        List<DispensingRecordLine> partial = lineRepository.findPartialSupplies(
                businessId, Instant.now().minus(OWING_WINDOW_DAYS, ChronoUnit.DAYS), PageRequest.of(0, MAX_ROWS));
        if (partial.isEmpty()) {
            return List.of();
        }
        Set<String> lineIds = new LinkedHashSet<>();
        Set<String> recordIds = new LinkedHashSet<>();
        for (DispensingRecordLine l : partial) {
            lineIds.add(l.getId());
            recordIds.add(l.getRecordId());
        }
        List<DispensingRecordLine> settling = lineRepository.findByBusinessIdAndSettlesLineIdIn(businessId, lineIds);
        for (DispensingRecordLine s : settling) {
            recordIds.add(s.getRecordId());
        }
        Map<String, DispensingRecord> records = new LinkedHashMap<>();
        for (DispensingRecord r : recordRepository.findByBusinessIdAndIdIn(businessId, recordIds)) {
            records.put(r.getId(), r);
        }
        Map<String, BigDecimal> settled = new LinkedHashMap<>();
        for (DispensingRecordLine s : settling) {
            DispensingRecord sr = records.get(s.getRecordId());
            if (sr != null && sr.getVoidedAt() == null) {
                settled.merge(s.getSettlesLineId(), s.getQuantity(), BigDecimal::add);
            }
        }
        List<Owing> out = new ArrayList<>();
        for (DispensingRecordLine l : partial) {
            DispensingRecord r = records.get(l.getRecordId());
            if (r == null || r.getVoidedAt() != null) {
                continue;
            }
            if (onlyBranchId != null && !onlyBranchId.equals(r.getBranchId())) {
                continue;
            }
            BigDecimal supplied = l.getQuantity().add(settled.getOrDefault(l.getId(), BigDecimal.ZERO));
            BigDecimal outstanding = l.getQuantityPrescribed().subtract(supplied);
            if (outstanding.signum() <= 0) {
                continue;
            }
            out.add(new Owing(
                    l.getId(), r.getId(), r.getSaleId(), r.getBranchId(), r.getCreatedAt(),
                    l.getItemId(), l.getItemName(), l.getQuantityPrescribed(), supplied, outstanding,
                    r.getPatientId(), r.getPatientName(), r.getPatientPhone(),
                    r.getPrescriberName(), r.getPrescriptionRef()));
        }
        audit(businessId, viewerUserId, out.size());
        return out;
    }

    /**
     * One patient's outstanding balances, for the dispensing dialog at the till, so a sale that
     * supplies a balance can be tied to the line that left it.
     */
    @Transactional(readOnly = true)
    public List<Owing> owingsForPatient(String businessId, String patientId, String viewerUserId) {
        if (patientId == null || patientId.isBlank()) {
            return List.of();
        }
        return owings(businessId, null, viewerUserId).stream()
                .filter(o -> patientId.strip().equals(o.patientId()))
                .toList();
    }

    /** Supplies that ran out in the last two weeks or run out in the next one. */
    @Transactional(readOnly = true)
    public List<RefillDue> refillsDue(String businessId, String onlyBranchId, String viewerUserId) {
        LocalDate today = LocalDate.now(SHOP_ZONE);
        List<DispensingRecordLine> lines = lineRepository
                .findByBusinessIdAndRefillDueOnBetweenOrderByRefillDueOnAscIdAsc(
                        businessId, today.minusDays(REFILL_DAYS_BACK), today.plusDays(REFILL_DAYS_AHEAD),
                        PageRequest.of(0, MAX_ROWS));
        if (lines.isEmpty()) {
            return List.of();
        }
        Set<String> recordIds = new LinkedHashSet<>();
        for (DispensingRecordLine l : lines) {
            recordIds.add(l.getRecordId());
        }
        Map<String, DispensingRecord> records = new LinkedHashMap<>();
        Set<String> patientIds = new LinkedHashSet<>();
        for (DispensingRecord r : recordRepository.findByBusinessIdAndIdIn(businessId, recordIds)) {
            records.put(r.getId(), r);
            if (r.getPatientId() != null) {
                patientIds.add(r.getPatientId());
            }
        }
        Map<String, PharmacyPatient> patients = new LinkedHashMap<>();
        if (!patientIds.isEmpty()) {
            for (PharmacyPatient p : patientRepository.findByBusinessIdAndIdIn(businessId, patientIds)) {
                if (p.getDeletedAt() == null) {
                    patients.put(p.getId(), p);
                }
            }
        }
        List<RefillDue> out = new ArrayList<>();
        for (DispensingRecordLine l : lines) {
            DispensingRecord r = records.get(l.getRecordId());
            if (r == null || r.getVoidedAt() != null) {
                continue;
            }
            if (onlyBranchId != null && !onlyBranchId.equals(r.getBranchId())) {
                continue;
            }
            PharmacyPatient p = r.getPatientId() == null ? null : patients.get(r.getPatientId());
            out.add(new RefillDue(
                    l.getId(), r.getId(), r.getBranchId(), l.getRefillDueOn(), l.getItemId(), l.getItemName(),
                    p == null ? null : p.getId(),
                    p != null ? p.getName() : r.getPatientName(),
                    p != null ? p.getPhone() : r.getPatientPhone(),
                    p != null && p.isReminderConsent()));
        }
        audit(businessId, viewerUserId, out.size());
        return out;
    }

    private void audit(String businessId, String viewerUserId, int count) {
        if (count == 0) {
            return;
        }
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("records", count);
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, AuditEventTypes.PHARMACY_RECORD_VIEWED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(viewerUserId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .newState(state)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit follow-up view business={}: {}", businessId, ex.toString());
        }
    }
}
