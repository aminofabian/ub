package zelisline.ub.suppliers.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.api.dto.CreateSupplierContactRequest;
import zelisline.ub.suppliers.api.dto.CreateSupplierRequest;
import zelisline.ub.suppliers.api.dto.PatchSupplierContactRequest;
import zelisline.ub.suppliers.api.dto.PatchSupplierRequest;
import zelisline.ub.suppliers.api.dto.SupplierContactResponse;
import zelisline.ub.suppliers.api.dto.SupplierResponse;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public Page<SupplierResponse> listSuppliers(String businessId, String searchRaw, String statusRaw, Pageable pageable) {
        String q = blankToNull(searchRaw);
        String st = blankToNull(statusRaw);
        Pageable pg = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return supplierRepository.searchSuppliers(businessId, q, st, pg).map(SupplierService::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(String businessId, String supplierId) {
        Supplier s = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        return toResponse(s);
    }

    @Transactional
    public SupplierResponse createSupplier(String businessId, CreateSupplierRequest request) {
        return createSupplier(businessId, request, null);
    }

    @Transactional
    public SupplierResponse createSupplier(String businessId, CreateSupplierRequest request, String actorUserId) {
        assertNameAvailable(businessId, request.name(), null);
        String code = blankToNull(request.code());
        if (code != null) {
            assertCodeAvailable(businessId, code, null);
        }
        Supplier s = new Supplier();
        s.setBusinessId(businessId);
        s.setName(request.name().trim());
        s.setCode(code);
        s.setSupplierType(firstOrDefault(request.supplierType(), "distributor"));
        s.setVatPin(blankToNull(request.vatPin()));
        s.setTaxExempt(Boolean.TRUE.equals(request.taxExempt()));
        s.setCreditTermsDays(request.creditTermsDays());
        s.setCreditLimit(request.creditLimit());
        s.setStatus(firstOrDefault(request.status(), "active"));
        s.setNotes(blankToNull(request.notes()));
        s.setPaymentMethodPreferred(blankToNull(request.paymentMethodPreferred()));
        s.setPaymentDetails(blankToNull(request.paymentDetails()));
        applyPayoutFields(s, request.payoutType(), request.payoutPhone(), null);
        try {
            supplierRepository.save(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use", ex);
        }
        publishSupplierEvent(businessId, s, actorUserId, AuditEventTypes.SUPPLIER_CREATED, null);
        return toResponse(s);
    }

    @Transactional
    public SupplierResponse patchSupplier(String businessId, String supplierId, PatchSupplierRequest patch, String actorUserId) {
        Supplier s = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        Map<String, Object> oldState = supplierSnapshot(s);
        if (SupplierCodes.SYSTEM_UNASSIGNED.equals(s.getCode())) {
            throwSystemSupplierMutationIfRestricted(patch, s);
        }
        if (patch.name() != null) {
            if (patch.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty");
            }
            assertNameAvailable(businessId, patch.name(), supplierId);
            s.setName(patch.name().trim());
        }
        if (patch.code() != null) {
            String code = blankToNull(patch.code());
            if (code != null) {
                assertCodeAvailable(businessId, code, supplierId);
            }
            s.setCode(code);
        }
        if (patch.supplierType() != null) {
            s.setSupplierType(blankToNull(patch.supplierType()));
            if (s.getSupplierType() == null) {
                s.setSupplierType("distributor");
            }
        }
        if (patch.vatPin() != null) {
            s.setVatPin(blankToNull(patch.vatPin()));
        }
        if (patch.taxExempt() != null) {
            s.setTaxExempt(patch.taxExempt());
        }
        if (patch.creditTermsDays() != null) {
            s.setCreditTermsDays(patch.creditTermsDays());
        }
        if (patch.creditLimit() != null) {
            s.setCreditLimit(patch.creditLimit());
        }
        if (patch.status() != null) {
            String st = blankToNull(patch.status());
            if (st == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status cannot be empty");
            }
            s.setStatus(st);
        }
        if (patch.notes() != null) {
            s.setNotes(blankToNull(patch.notes()));
        }
        if (patch.paymentMethodPreferred() != null) {
            s.setPaymentMethodPreferred(blankToNull(patch.paymentMethodPreferred()));
        }
        if (patch.paymentDetails() != null) {
            s.setPaymentDetails(blankToNull(patch.paymentDetails()));
        }
        if (patch.payoutType() != null || patch.payoutPhone() != null || patch.kopokopoExternalRecipientUrl() != null) {
            applyPayoutFields(
                    s,
                    patch.payoutType(),
                    patch.payoutPhone(),
                    patch.kopokopoExternalRecipientUrl());
        }
        try {
            supplierRepository.save(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use", ex);
        }
        Map<String, Object> newState = supplierSnapshot(s);
        Map<String, Object> diff = compactDiff(oldState, newState);
        if (!diff.isEmpty()) {
            publishSupplierEvent(businessId, s, actorUserId, AuditEventTypes.SUPPLIER_UPDATED, diff);
        }
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public List<SupplierContactResponse> listContacts(String businessId, String supplierId) {
        assertSupplierInBusiness(businessId, supplierId);
        return supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(supplierId).stream()
                .map(SupplierService::toContactResponse)
                .toList();
    }

    @Transactional
    public SupplierContactResponse addContact(String businessId, String supplierId, CreateSupplierContactRequest body) {
        return addContact(businessId, supplierId, body, null);
    }

    @Transactional
    public SupplierContactResponse addContact(String businessId, String supplierId, CreateSupplierContactRequest body, String actorUserId) {
        Supplier supplier = assertSupplierInBusiness(businessId, supplierId);
        if (Boolean.TRUE.equals(body.primaryContact())) {
            demotePrimaryContacts(supplierId);
        }
        SupplierContact c = new SupplierContact();
        c.setSupplierId(supplierId);
        c.setName(blankToNull(body.name()));
        c.setRoleLabel(blankToNull(body.roleLabel()));
        c.setPhone(blankToNull(body.phone()));
        c.setEmail(blankToNull(body.email()));
        c.setPrimaryContact(Boolean.TRUE.equals(body.primaryContact()));
        supplierContactRepository.save(c);
        publishSupplierEvent(businessId, supplier, actorUserId, AuditEventTypes.SUPPLIER_CONTACT_ADDED,
                map("contact", map(
                        "id", c.getId(),
                        "name", c.getName(),
                        "roleLabel", c.getRoleLabel(),
                        "phone", c.getPhone(),
                        "email", c.getEmail(),
                        "primaryContact", c.isPrimaryContact())));
        return toContactResponse(c);
    }

    @Transactional
    public SupplierContactResponse patchContact(
            String businessId,
            String supplierId,
            String contactId,
            PatchSupplierContactRequest patch
    ) {
        return patchContact(businessId, supplierId, contactId, patch, null);
    }

    @Transactional
    public SupplierContactResponse patchContact(
            String businessId,
            String supplierId,
            String contactId,
            PatchSupplierContactRequest patch,
            String actorUserId
    ) {
        Supplier supplier = assertSupplierInBusiness(businessId, supplierId);
        SupplierContact c = supplierContactRepository.findByIdAndSupplierId(contactId, supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
        Map<String, Object> oldState = contactSnapshot(c);
        if (Boolean.TRUE.equals(patch.primaryContact())) {
            demotePrimaryContacts(supplierId);
            c.setPrimaryContact(true);
        } else if (patch.primaryContact() != null && !patch.primaryContact()) {
            c.setPrimaryContact(false);
        }
        if (patch.name() != null) {
            c.setName(blankToNull(patch.name()));
        }
        if (patch.roleLabel() != null) {
            c.setRoleLabel(blankToNull(patch.roleLabel()));
        }
        if (patch.phone() != null) {
            c.setPhone(blankToNull(patch.phone()));
        }
        if (patch.email() != null) {
            c.setEmail(blankToNull(patch.email()));
        }
        supplierContactRepository.save(c);
        Map<String, Object> newState = contactSnapshot(c);
        Map<String, Object> diff = compactDiff(oldState, newState);
        if (!diff.isEmpty()) {
            publishSupplierEvent(businessId, supplier, actorUserId, AuditEventTypes.SUPPLIER_CONTACT_UPDATED, diff);
        }
        return toContactResponse(c);
    }

    @Transactional
    public void deleteContact(String businessId, String supplierId, String contactId) {
        deleteContact(businessId, supplierId, contactId, null);
    }

    @Transactional
    public void deleteContact(String businessId, String supplierId, String contactId, String actorUserId) {
        assertSupplierInBusiness(businessId, supplierId);
        SupplierContact c = supplierContactRepository.findByIdAndSupplierId(contactId, supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
        supplierContactRepository.delete(c);
    }

    private Supplier assertSupplierInBusiness(String businessId, String supplierId) {
        return supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private void demotePrimaryContacts(String supplierId) {
        List<SupplierContact> contacts = supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(supplierId);
        for (SupplierContact c : contacts) {
            if (c.isPrimaryContact()) {
                c.setPrimaryContact(false);
                supplierContactRepository.save(c);
            }
        }
    }

    private void assertNameAvailable(String businessId, String name, String ignoreId) {
        if (supplierRepository.existsDuplicateName(businessId, name.trim(), ignoreId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier name already in use");
        }
    }

    private void assertCodeAvailable(String businessId, String code, String ignoreId) {
        supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, code)
                .ifPresent(s -> {
                    if (ignoreId == null || !s.getId().equals(ignoreId)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use");
                    }
                });
    }

    private void throwSystemSupplierMutationIfRestricted(PatchSupplierRequest patch, Supplier s) {
        if (!SupplierCodes.SYSTEM_UNASSIGNED.equals(s.getCode())) {
            return;
        }
        if (patch.code() != null && !SupplierCodes.SYSTEM_UNASSIGNED.equals(blankToNull(patch.code()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot change synthetic supplier code");
        }
        if (patch.name() != null && !patch.name().trim().equals(s.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot rename synthetic migration supplier");
        }
    }

    private static void applyPayoutFields(
            Supplier s,
            String payoutType,
            String payoutPhone,
            String kopokopoRecipientUrl
    ) {
        if (payoutType != null) {
            String t = blankToNull(payoutType);
            if (t == null) {
                s.setPayoutType(SupplierPayoutTypes.MANUAL);
            } else {
                String norm = t.toLowerCase();
                if (!SupplierPayoutTypes.MANUAL.equals(norm) && !SupplierPayoutTypes.MOBILE_WALLET.equals(norm)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "payoutType must be manual or mobile_wallet");
                }
                s.setPayoutType(norm);
            }
        }
        if (payoutPhone != null) {
            String raw = blankToNull(payoutPhone);
            if (raw == null) {
                s.setPayoutPhone(null);
            } else {
                String normalized = StkPhoneNormalizer.normalize(raw);
                if (normalized == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payout phone number");
                }
                s.setPayoutPhone(normalized);
            }
        }
        if (kopokopoRecipientUrl != null) {
            s.setKopokopoExternalRecipientUrl(blankToNull(kopokopoRecipientUrl));
        }
    }

    private static SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(
                s.getId(),
                s.getName(),
                s.getCode(),
                s.getSupplierType(),
                s.getVatPin(),
                s.isTaxExempt(),
                s.getCreditTermsDays(),
                s.getCreditLimit(),
                s.getRating(),
                s.getStatus(),
                s.getNotes(),
                s.getPaymentMethodPreferred(),
                s.getPaymentDetails(),
                s.getPayoutType(),
                s.getPayoutPhone(),
                s.getKopokopoExternalRecipientUrl(),
                s.getVersion(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    private static SupplierContactResponse toContactResponse(SupplierContact c) {
        return new SupplierContactResponse(
                c.getId(),
                c.getName(),
                c.getRoleLabel(),
                c.getPhone(),
                c.getEmail(),
                c.isPrimaryContact(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String firstOrDefault(String value, String def) {
        String v = blankToNull(value);
        return v != null ? v : def;
    }

    private Map<String, Object> supplierSnapshot(Supplier s) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", s.getName());
        snapshot.put("code", s.getCode());
        snapshot.put("supplierType", s.getSupplierType());
        snapshot.put("vatPin", s.getVatPin());
        snapshot.put("taxExempt", s.isTaxExempt());
        snapshot.put("creditTermsDays", s.getCreditTermsDays());
        snapshot.put("creditLimit", s.getCreditLimit());
        snapshot.put("status", s.getStatus());
        snapshot.put("notes", s.getNotes());
        snapshot.put("paymentMethodPreferred", s.getPaymentMethodPreferred());
        snapshot.put("paymentDetails", s.getPaymentDetails());
        snapshot.put("payoutType", s.getPayoutType());
        snapshot.put("payoutPhone", s.getPayoutPhone());
        snapshot.put("kopokopoExternalRecipientUrl", s.getKopokopoExternalRecipientUrl());
        return snapshot;
    }

    private Map<String, Object> contactSnapshot(SupplierContact c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", c.getName());
        snapshot.put("roleLabel", c.getRoleLabel());
        snapshot.put("phone", c.getPhone());
        snapshot.put("email", c.getEmail());
        snapshot.put("primaryContact", c.isPrimaryContact());
        return snapshot;
    }

    private Map<String, Object> compactDiff(Map<String, Object> oldState, Map<String, Object> newState) {
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String key : oldState.keySet()) {
            Object oldVal = oldState.get(key);
            Object newVal = newState.get(key);
            if (!Objects.equals(oldVal, newVal)) {
                diff.put(key, map("old", oldVal, "new", newVal));
            }
        }
        return diff;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private void publishSupplierEvent(String businessId, Supplier s, String actorUserId,
                                      String eventType, Object diff) {
        AuditEventActorType actorType = actorUserId != null && !actorUserId.isBlank()
                ? AuditEventActorType.USER
                : AuditEventActorType.SYSTEM;
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SUPPLIERS, eventType, AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(actorUserId, actorType)
                .target("supplier", s.getId())
                .targetLabel(s.getName() + (s.getCode() != null ? " (" + s.getCode() + ")" : ""))
                .source("web_admin")
                .diff(diff)
                .build());
    }
}
