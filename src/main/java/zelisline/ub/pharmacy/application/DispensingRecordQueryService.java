package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.api.dto.DispensingRecordPageResponse;
import zelisline.ub.pharmacy.api.dto.DispensingRecordResponse;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.domain.PharmacyStaffCredential;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffCredentialRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * The dispensing log. Records hold prescriber and (optionally) patient details, so every read is
 * permission-gated at the controller and leaves an audit event saying who looked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispensingRecordQueryService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RANGE_DAYS = 366;

    private final DispensingRecordRepository recordRepository;
    private final DispensingRecordLineRepository lineRepository;
    private final PharmacyStaffCredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final SaleRepository saleRepository;
    private final zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository correctionRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public DispensingRecordPageResponse list(
            String businessId,
            String branchId,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String viewerUserId
    ) {
        LocalDate today = LocalDate.now(SHOP_ZONE);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date is after the end date");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a range of one year or less");
        }
        Instant fromTs = start.atStartOfDay(SHOP_ZONE).toInstant();
        Instant toTs = end.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        String branch = branchId == null || branchId.isBlank() ? null : branchId.strip();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        List<DispensingRecord> records = recordRepository.search(
                businessId, branch, fromTs, toTs, PageRequest.of(safePage, safeSize));
        long total = recordRepository.countSearch(businessId, branch, fromTs, toTs);
        List<DispensingRecordResponse> out = toResponses(businessId, records);
        auditView(businessId, viewerUserId, null, out.size());
        return new DispensingRecordPageResponse(out, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public DispensingRecordResponse get(String businessId, String recordId, String viewerUserId) {
        return get(businessId, recordId, viewerUserId, null);
    }

    /** As {@link #get(String, String, String)}; a non-null {@code onlyBranchId} hides other branches' records. */
    @Transactional(readOnly = true)
    public DispensingRecordResponse get(
            String businessId, String recordId, String viewerUserId, String onlyBranchId) {
        DispensingRecord r = recordRepository.findByIdAndBusinessId(recordId, businessId)
                .filter(x -> onlyBranchId == null || onlyBranchId.equals(x.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found"));
        DispensingRecordResponse out = toResponses(businessId, List.of(r)).getFirst();
        auditView(businessId, viewerUserId, r.getId(), 1);
        return out;
    }

    /** Records for one sale (receipt and sale-detail screens). Audited like any other read. */
    @Transactional(readOnly = true)
    public List<DispensingRecordResponse> forSale(String businessId, String saleId, String viewerUserId) {
        return forSale(businessId, saleId, viewerUserId, null);
    }

    /** As {@link #forSale(String, String, String)}; a non-null {@code onlyBranchId} hides other branches' records. */
    @Transactional(readOnly = true)
    public List<DispensingRecordResponse> forSale(
            String businessId, String saleId, String viewerUserId, String onlyBranchId) {
        List<DispensingRecord> records = recordRepository.findByBusinessIdAndSaleId(businessId, saleId).stream()
                .filter(x -> onlyBranchId == null || onlyBranchId.equals(x.getBranchId()))
                .toList();
        List<DispensingRecordResponse> out = toResponses(businessId, records);
        if (!out.isEmpty()) {
            auditView(businessId, viewerUserId, null, out.size());
        }
        return out;
    }

    /** A patient's dispensing history, newest first (at most 200 records). Audited. */
    @Transactional(readOnly = true)
    public List<DispensingRecordResponse> forPatient(String businessId, String patientId, String viewerUserId) {
        List<DispensingRecordResponse> out = toResponses(businessId,
                recordRepository.findByBusinessIdAndPatientIdOrderByCreatedAtDescIdDesc(
                        businessId, patientId, PageRequest.of(0, 200)));
        auditView(businessId, viewerUserId, null, out.size());
        return out;
    }

    private List<DispensingRecordResponse> toResponses(String businessId, List<DispensingRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<String> recordIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> saleIds = new LinkedHashSet<>();
        for (DispensingRecord r : records) {
            recordIds.add(r.getId());
            saleIds.add(r.getSaleId());
            if (r.getDispensedBy() != null) {
                userIds.add(r.getDispensedBy());
            }
            if (r.getSoldBy() != null) {
                userIds.add(r.getSoldBy());
            }
            if (r.getVerifiedBy() != null) {
                userIds.add(r.getVerifiedBy());
            }
        }
        List<zelisline.ub.pharmacy.domain.DispensingRecordCorrection> correctionRows =
                correctionRepository.findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(businessId, recordIds);
        for (var c : correctionRows) {
            userIds.add(c.getCreatedBy());
        }
        Map<String, List<DispensingRecordResponse.Line>> linesByRecord = new LinkedHashMap<>();
        for (DispensingRecordLine l : lineRepository
                .findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(businessId, recordIds)) {
            linesByRecord.computeIfAbsent(l.getRecordId(), k -> new ArrayList<>())
                    .add(new DispensingRecordResponse.Line(
                            l.getItemId(),
                            l.getItemName(),
                            l.getDispensingClass(),
                            l.getQuantity(),
                            l.getInstructions(),
                            l.getLotNumbers(),
                            l.getId(),
                            l.getQuantityPrescribed(),
                            l.getRefillDueOn(),
                            l.getSettlesLineId(),
                            l.getQuantityReturned()));
        }
        Map<String, String> userNames = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            if (businessId.equals(u.getBusinessId())) {
                userNames.put(u.getId(), u.getName());
            }
        }
        Map<String, PharmacyStaffCredential> creds = new LinkedHashMap<>();
        for (PharmacyStaffCredential c : credentialRepository.findByBusinessIdAndUserIdIn(businessId, userIds)) {
            creds.put(c.getUserId(), c);
        }
        Map<String, Long> receiptNos = new LinkedHashMap<>();
        for (Sale s : saleRepository.findAllById(saleIds)) {
            if (businessId.equals(s.getBusinessId())) {
                receiptNos.put(s.getId(), s.getReceiptNo());
            }
        }
        Map<String, List<DispensingRecordResponse.Correction>> correctionsByRecord = new LinkedHashMap<>();
        for (var c : correctionRows) {
            correctionsByRecord.computeIfAbsent(c.getRecordId(), k -> new ArrayList<>())
                    .add(new DispensingRecordResponse.Correction(
                            c.getId(), c.getNote(), userNames.get(c.getCreatedBy()), c.getCreatedAt()));
        }
        List<DispensingRecordResponse> out = new ArrayList<>(records.size());
        for (DispensingRecord r : records) {
            PharmacyStaffCredential c = r.getDispensedBy() == null ? null : creds.get(r.getDispensedBy());
            out.add(new DispensingRecordResponse(
                    r.getId(),
                    r.getSaleId(),
                    receiptNos.get(r.getSaleId()),
                    r.getBranchId(),
                    r.getCreatedAt(),
                    r.getDispensedBy(),
                    r.getDispensedBy() == null ? null : userNames.get(r.getDispensedBy()),
                    c == null ? null : c.getTitle(),
                    c == null ? null : c.getRegistrationNo(),
                    r.getSoldBy() == null ? null : userNames.get(r.getSoldBy()),
                    r.getApprovalId() != null,
                    r.getPrescriberName(),
                    r.getPrescriberRegNo(),
                    r.getPrescriptionRef(),
                    r.getPrescriptionDate(),
                    r.getPatientName(),
                    r.getPatientPhone(),
                    r.getVoidedAt(),
                    linesByRecord.getOrDefault(r.getId(), List.of()),
                    r.getPatientId(),
                    r.getVerifiedBy() == null ? null : userNames.get(r.getVerifiedBy()),
                    r.getVerifiedAt(),
                    correctionsByRecord.getOrDefault(r.getId(), List.of())));
        }
        return out;
    }

    private void auditView(String businessId, String viewerUserId, String recordId, int count) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("records", count);
            var builder = auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, AuditEventTypes.PHARMACY_RECORD_VIEWED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(viewerUserId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .newState(state);
            if (recordId != null) {
                builder.target("dispensing_record", recordId);
            }
            auditEventPublisher.publish(builder.build());
        } catch (Exception ex) {
            log.warn("Failed to audit dispensing-record view business={}: {}", businessId, ex.toString());
        }
    }
}
