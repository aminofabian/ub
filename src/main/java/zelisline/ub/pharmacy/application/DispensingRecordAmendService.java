package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordCorrection;
import zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;

/**
 * The two things that may happen to a dispensing record after it is written (ADR 0033): a second
 * person checks it, once; and anyone who may dispense adds a note saying what was written
 * wrongly. Neither changes what the record says.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispensingRecordAmendService {

    private final DispensingRecordRepository recordRepository;
    private final DispensingRecordCorrectionRepository correctionRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /**
     * Stamps the record as checked by {@code userId}. The checker must be someone other than the
     * person who dispensed it; a record is checked once and a voided one not at all.
     */
    @Transactional
    public void verify(String businessId, String recordId, String userId, String onlyBranchId) {
        DispensingRecord r = require(businessId, recordId, onlyBranchId);
        if (r.getVoidedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sale was voided, so there is nothing to check");
        }
        if (r.getVerifiedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This record has already been checked");
        }
        if (userId.equals(r.getDispensedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You dispensed this. The second check has to be done by someone else.");
        }
        r.setVerifiedBy(userId);
        r.setVerifiedAt(Instant.now());
        recordRepository.save(r);
        audit(businessId, userId, AuditEventTypes.PHARMACY_RECORD_VERIFIED, r.getId());
    }

    @Transactional
    public void addCorrection(String businessId, String recordId, String note, String userId, String onlyBranchId) {
        DispensingRecord r = require(businessId, recordId, onlyBranchId);
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what was written wrongly and what is right");
        }
        DispensingRecordCorrection c = new DispensingRecordCorrection();
        c.setBusinessId(businessId);
        c.setRecordId(r.getId());
        c.setNote(text.length() > 1000 ? text.substring(0, 1000) : text);
        c.setCreatedBy(userId);
        correctionRepository.save(c);
        audit(businessId, userId, AuditEventTypes.PHARMACY_RECORD_CORRECTED, r.getId());
    }

    private DispensingRecord require(String businessId, String recordId, String onlyBranchId) {
        return recordRepository.findByIdAndBusinessId(recordId, businessId)
                .filter(x -> onlyBranchId == null || onlyBranchId.equals(x.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found"));
    }

    private void audit(String businessId, String userId, String type, String recordId) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("recordId", recordId);
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, type, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .target("dispensing_record", recordId)
                    .newState(state)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit dispensing-record amendment business={}: {}", businessId, ex.toString());
        }
    }
}
