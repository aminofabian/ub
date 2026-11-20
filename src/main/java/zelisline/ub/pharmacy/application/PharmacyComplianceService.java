package zelisline.ub.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
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
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.AdverseEventRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.AdverseEventResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.SupplierLicenceRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.SupplierLicenceResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.TemperatureRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos.TemperatureResponse;
import zelisline.ub.pharmacy.domain.PharmacyAdverseEvent;
import zelisline.ub.pharmacy.domain.PharmacyPatient;
import zelisline.ub.pharmacy.domain.PharmacySupplierLicence;
import zelisline.ub.pharmacy.domain.PharmacyTemperatureLog;
import zelisline.ub.pharmacy.repository.PharmacyAdverseEventRepository;
import zelisline.ub.pharmacy.repository.PharmacyPatientRepository;
import zelisline.ub.pharmacy.repository.PharmacySupplierLicenceRepository;
import zelisline.ub.pharmacy.repository.PharmacyTemperatureLogRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * The record-keeping a pharmacy does beside selling (ADR 0033, Phase 2): storage temperatures,
 * suppliers' licences, and notes on suspected adverse reactions. Kiosk stores what the shop
 * enters; it verifies nothing with a regulator and sends nothing to one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PharmacyComplianceService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final int MAX_ROWS = 500;

    private final PharmacyTemperatureLogRepository temperatureRepository;
    private final PharmacySupplierLicenceRepository licenceRepository;
    private final PharmacyAdverseEventRepository adverseEventRepository;
    private final PharmacyPatientRepository patientRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    // ── Temperature ───────────────────────────────────────────────────

    @Transactional
    public TemperatureResponse recordTemperature(String businessId, TemperatureRequest req, String userId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.branchId().strip(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch"));
        if (req.minC().compareTo(req.maxC()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The lowest allowed temperature is above the highest");
        }
        boolean out = req.readingC().compareTo(req.minC()) < 0 || req.readingC().compareTo(req.maxC()) > 0;
        String note = req.actionNote() == null || req.actionNote().isBlank() ? null : req.actionNote().strip();
        if (out && note == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This reading is outside the allowed range. Say what you did about it.");
        }
        PharmacyTemperatureLog t = new PharmacyTemperatureLog();
        t.setBusinessId(businessId);
        t.setBranchId(req.branchId().strip());
        t.setStorageName(req.storageName().strip());
        t.setReadingC(req.readingC());
        t.setMinC(req.minC());
        t.setMaxC(req.maxC());
        t.setOutOfRange(out);
        t.setActionNote(note);
        t.setRecordedBy(userId);
        temperatureRepository.save(t);
        if (out) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("storage", t.getStorageName());
            state.put("readingC", t.getReadingC());
            audit(businessId, userId, AuditEventTypes.PHARMACY_TEMPERATURE_OUT_OF_RANGE, AuditEventSeverity.WARN,
                    "pharmacy_temperature_log", t.getId(), state);
        }
        return toResponse(t, userNames(businessId, List.of(userId)));
    }

    @Transactional(readOnly = true)
    public List<TemperatureResponse> temperatures(String businessId, String branchId, Integer days) {
        int window = days == null ? 30 : Math.min(366, Math.max(1, days));
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
        List<PharmacyTemperatureLog> rows = branchId == null || branchId.isBlank()
                ? temperatureRepository.findByBusinessIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                        businessId, since, PageRequest.of(0, MAX_ROWS))
                : temperatureRepository
                        .findByBusinessIdAndBranchIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                                businessId, branchId.strip(), since, PageRequest.of(0, MAX_ROWS));
        Set<String> userIds = new LinkedHashSet<>();
        for (PharmacyTemperatureLog t : rows) {
            userIds.add(t.getRecordedBy());
        }
        Map<String, String> names = userNames(businessId, userIds);
        return rows.stream().map(t -> toResponse(t, names)).toList();
    }

    private static TemperatureResponse toResponse(PharmacyTemperatureLog t, Map<String, String> names) {
        return new TemperatureResponse(
                t.getId(), t.getBranchId(), t.getStorageName(), t.getReadingC(), t.getMinC(), t.getMaxC(),
                t.isOutOfRange(), t.getActionNote(), names.get(t.getRecordedBy()), t.getRecordedAt());
    }

    // ── Supplier licences ─────────────────────────────────────────────

    /** Every active supplier, with the licence recorded for it (blank when none is). */
    @Transactional(readOnly = true)
    public List<SupplierLicenceResponse> supplierLicences(String businessId) {
        Map<String, PharmacySupplierLicence> bySupplier = new LinkedHashMap<>();
        for (PharmacySupplierLicence l : licenceRepository.findByBusinessId(businessId)) {
            bySupplier.put(l.getSupplierId(), l);
        }
        List<SupplierLicenceResponse> out = new ArrayList<>();
        for (Supplier s : supplierRepository.findActiveByBusinessId(businessId)) {
            out.add(toResponse(s, bySupplier.get(s.getId())));
        }
        out.sort((a, b) -> String.valueOf(a.supplierName()).compareToIgnoreCase(String.valueOf(b.supplierName())));
        return out;
    }

    @Transactional
    public SupplierLicenceResponse saveSupplierLicence(
            String businessId, String supplierId, SupplierLicenceRequest req, String userId) {
        Supplier s = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        PharmacySupplierLicence l = licenceRepository.findByBusinessIdAndSupplierId(businessId, s.getId())
                .orElseGet(() -> {
                    PharmacySupplierLicence created = new PharmacySupplierLicence();
                    created.setBusinessId(businessId);
                    created.setSupplierId(s.getId());
                    return created;
                });
        l.setLicenceNo(blankToNull(req.licenceNo()));
        l.setExpiresOn(req.expiresOn());
        l.setNote(blankToNull(req.note()));
        l.setUpdatedBy(userId);
        licenceRepository.save(l);
        return toResponse(s, l);
    }

    private static SupplierLicenceResponse toResponse(Supplier s, PharmacySupplierLicence l) {
        Long daysLeft = l == null || l.getExpiresOn() == null
                ? null
                : ChronoUnit.DAYS.between(LocalDate.now(SHOP_ZONE), l.getExpiresOn());
        return new SupplierLicenceResponse(
                s.getId(), s.getName(),
                l == null ? null : l.getLicenceNo(),
                l == null ? null : l.getExpiresOn(),
                daysLeft,
                l == null ? null : l.getNote());
    }

    // ── Adverse events ────────────────────────────────────────────────

    @Transactional
    public AdverseEventResponse recordAdverseEvent(String businessId, AdverseEventRequest req, String userId) {
        if (req.occurredOn().isAfter(LocalDate.now(SHOP_ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date is in the future");
        }
        PharmacyAdverseEvent e = new PharmacyAdverseEvent();
        e.setBusinessId(businessId);
        e.setOccurredOn(req.occurredOn());
        String itemId = blankToNull(req.itemId());
        if (itemId != null) {
            // Keep the link only when the product is this shop's.
            itemId = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(List.of(itemId), businessId).isEmpty()
                    ? null
                    : itemId;
        }
        e.setItemId(itemId);
        e.setProductName(req.productName().strip());
        e.setLotNumber(blankToNull(req.lotNumber()));
        String patientId = blankToNull(req.patientId());
        if (patientId != null) {
            patientId = patientRepository.findByIdAndBusinessIdAndDeletedAtIsNull(patientId, businessId)
                    .map(PharmacyPatient::getId)
                    .orElse(null);
        }
        e.setPatientId(patientId);
        e.setDescription(req.description().strip());
        e.setSeverity(req.severity());
        e.setActionTaken(blankToNull(req.actionTaken()));
        e.setRecordedBy(userId);
        adverseEventRepository.save(e);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("severity", e.getSeverity());
        audit(businessId, userId, AuditEventTypes.PHARMACY_ADVERSE_EVENT_RECORDED, AuditEventSeverity.INFO,
                "pharmacy_adverse_event", e.getId(), state);
        return toResponses(businessId, List.of(e)).getFirst();
    }

    /** Marks a note as passed on to the regulator or the manufacturer. The shop does the reporting itself. */
    @Transactional
    public AdverseEventResponse markReported(String businessId, String eventId, String externalRef) {
        PharmacyAdverseEvent e = adverseEventRepository.findByIdAndBusinessId(eventId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));
        e.setReportedExternally(true);
        if (e.getReportedAt() == null) {
            e.setReportedAt(Instant.now());
        }
        e.setExternalRef(blankToNull(externalRef));
        adverseEventRepository.save(e);
        return toResponses(businessId, List.of(e)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<AdverseEventResponse> adverseEvents(String businessId) {
        return toResponses(businessId,
                adverseEventRepository.findByBusinessIdOrderByCreatedAtDescIdDesc(businessId, PageRequest.of(0, MAX_ROWS)));
    }

    private List<AdverseEventResponse> toResponses(String businessId, List<PharmacyAdverseEvent> events) {
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> patientIds = new LinkedHashSet<>();
        for (PharmacyAdverseEvent e : events) {
            userIds.add(e.getRecordedBy());
            if (e.getPatientId() != null) {
                patientIds.add(e.getPatientId());
            }
        }
        Map<String, String> names = userNames(businessId, userIds);
        Map<String, String> patientNames = new LinkedHashMap<>();
        if (!patientIds.isEmpty()) {
            for (PharmacyPatient p : patientRepository.findByBusinessIdAndIdIn(businessId, patientIds)) {
                patientNames.put(p.getId(), p.getName());
            }
        }
        List<AdverseEventResponse> out = new ArrayList<>(events.size());
        for (PharmacyAdverseEvent e : events) {
            out.add(new AdverseEventResponse(
                    e.getId(), e.getOccurredOn(), e.getItemId(), e.getProductName(), e.getLotNumber(),
                    e.getPatientId(), e.getPatientId() == null ? null : patientNames.get(e.getPatientId()),
                    e.getDescription(), e.getSeverity(), e.getActionTaken(), e.isReportedExternally(),
                    e.getExternalRef(), e.getReportedAt(), names.get(e.getRecordedBy()), e.getCreatedAt()));
        }
        return out;
    }

    // ── Shared ────────────────────────────────────────────────────────

    private Map<String, String> userNames(String businessId, Collection<String> userIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        for (User u : userRepository.findAllById(userIds)) {
            if (businessId.equals(u.getBusinessId())) {
                out.put(u.getId(), u.getName());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private void audit(
            String businessId, String userId, String type, AuditEventSeverity severity,
            String targetType, String targetId, Map<String, Object> state) {
        try {
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SECURITY, type, severity)
                    .businessId(businessId)
                    .actor(userId, AuditEventActorType.USER)
                    .source("pharmacy")
                    .target(targetType, targetId)
                    .newState(state)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit pharmacy compliance event business={}: {}", businessId, ex.toString());
        }
    }
}
