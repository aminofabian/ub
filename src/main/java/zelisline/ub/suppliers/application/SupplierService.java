package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
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
import zelisline.ub.marketplace.application.MarketplaceSupplierPassportService;
import zelisline.ub.marketplace.application.SupplierIdentityIndexService;
import zelisline.ub.marketplace.application.SupplierIdentityNormalizer;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
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
    private final SupplierContactUniquenessService contactUniquenessService;
    private final SupplierIdentityIndexService supplierIdentityIndexService;
    private final MarketplaceSupplierPassportService passportService;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public Page<SupplierResponse> listSuppliers(String businessId, String searchRaw, String statusRaw, Pageable pageable) {
        String q = blankToNull(searchRaw);
        String st = blankToNull(statusRaw);
        Pageable pg = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return supplierRepository.searchSuppliers(businessId, q, st, pg).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(String businessId, String supplierId) {
        return getSupplier(businessId, supplierId, false);
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(String businessId, String supplierId, boolean includeDeleted) {
        Supplier s = (includeDeleted
                ? supplierRepository.findByIdAndBusinessId(supplierId, businessId)
                : supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId))
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
        applyPayoutFields(
                s,
                request.payoutType(),
                request.payoutPhone(),
                request.payoutTillNumber(),
                request.payoutPaybillNumber(),
                request.payoutPaybillAccount(),
                null);
        if (s.getPayoutPhone() != null) {
            contactUniquenessService.assertPhoneAvailable(businessId, s.getPayoutPhone(), null);
        }
        try {
            supplierRepository.save(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use", ex);
        }

        if (portalSettingsService.loadSingleton().isAutoPromoteOnCreate()) {
            var marketplace = passportService.createDraftPassport(
                    s.getName(),
                    s.getPayoutPhone(),
                    null,
                    s.getVatPin());
            s.setMarketplaceSupplierId(marketplace.getId());
            supplierRepository.save(s);
            if (!connectionRepository.existsByLocalSupplierId(s.getId())) {
                BusinessSupplierConnection connection = new BusinessSupplierConnection();
                connection.setBusinessId(businessId);
                connection.setMarketplaceSupplierId(marketplace.getId());
                connection.setLocalSupplierId(s.getId());
                connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
                connection.setCanViewPurchaseHistory(true);
                connectionRepository.save(connection);
            }
        }

        supplierIdentityIndexService.upsertTenantSupplier(s, s.getPayoutPhone(), null);
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
        if (patch.payoutType() != null
                || patch.payoutPhone() != null
                || patch.payoutTillNumber() != null
                || patch.payoutPaybillNumber() != null
                || patch.payoutPaybillAccount() != null
                || patch.kopokopoExternalRecipientUrl() != null) {
            applyPayoutFields(
                    s,
                    patch.payoutType(),
                    patch.payoutPhone(),
                    patch.payoutTillNumber(),
                    patch.payoutPaybillNumber(),
                    patch.payoutPaybillAccount(),
                    patch.kopokopoExternalRecipientUrl());
            if (patch.payoutPhone() != null && s.getPayoutPhone() != null) {
                contactUniquenessService.assertPhoneAvailable(businessId, s.getPayoutPhone(), supplierId);
            }
        }
        try {
            supplierRepository.save(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use", ex);
        }
        supplierIdentityIndexService.upsertTenantSupplier(s, s.getPayoutPhone(), null);
        Map<String, Object> newState = supplierSnapshot(s);
        Map<String, Object> diff = compactDiff(oldState, newState);
        if (!diff.isEmpty()) {
            publishSupplierEvent(businessId, s, actorUserId, AuditEventTypes.SUPPLIER_UPDATED, diff);
        }
        return toResponse(s);
    }

    @Transactional
    public void deleteSupplier(String businessId, String supplierId) {
        deleteSupplier(businessId, supplierId, null);
    }

    @Transactional
    public void deleteSupplier(String businessId, String supplierId, String actorUserId) {
        Supplier s = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (SupplierCodes.SYSTEM_UNASSIGNED.equals(s.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete the system unassigned supplier");
        }
        s.setDeletedAt(java.time.Instant.now());
        s.setStatus("inactive");
        supplierRepository.save(s);
        publishSupplierEvent(businessId, s, actorUserId, AuditEventTypes.SUPPLIER_DELETED, null);
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
        String phone = normalizeContactPhone(body.phone());
        String email = blankToNull(body.email());
        if (email != null) {
            email = SupplierIdentityNormalizer.normalizeEmail(email);
        }
        contactUniquenessService.assertPhoneAvailable(businessId, phone, supplierId);
        contactUniquenessService.assertEmailAvailable(businessId, email, supplierId);
        SupplierContact c = new SupplierContact();
        c.setSupplierId(supplierId);
        c.setName(blankToNull(body.name()));
        c.setRoleLabel(blankToNull(body.roleLabel()));
        c.setPhone(phone);
        c.setEmail(email);
        c.setPrimaryContact(Boolean.TRUE.equals(body.primaryContact()));
        supplierContactRepository.save(c);
        refreshIdentityIndex(supplier);
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
            String phone = normalizeContactPhone(patch.phone());
            contactUniquenessService.assertPhoneAvailable(businessId, phone, supplierId);
            c.setPhone(phone);
        }
        if (patch.email() != null) {
            String email = blankToNull(patch.email());
            if (email != null) {
                email = SupplierIdentityNormalizer.normalizeEmail(email);
            }
            contactUniquenessService.assertEmailAvailable(businessId, email, supplierId);
            c.setEmail(email);
        }
        supplierContactRepository.save(c);
        refreshIdentityIndex(supplier);
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

    private void refreshIdentityIndex(Supplier supplier) {
        String phone = supplier.getPayoutPhone();
        String email = null;
        for (SupplierContact contact : supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())) {
            if (phone == null && contact.getPhone() != null && !contact.getPhone().isBlank()) {
                phone = contact.getPhone();
            }
            if (email == null && contact.getEmail() != null && !contact.getEmail().isBlank()) {
                email = contact.getEmail();
            }
            if (contact.isPrimaryContact()) {
                if (contact.getPhone() != null && !contact.getPhone().isBlank()
                        && (supplier.getPayoutPhone() == null || supplier.getPayoutPhone().isBlank())) {
                    phone = contact.getPhone();
                }
                if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
                    email = contact.getEmail();
                }
            }
        }
        supplierIdentityIndexService.upsertTenantSupplier(supplier, phone, email);
    }

    private static String normalizeContactPhone(String raw) {
        String blank = blankToNull(raw);
        if (blank == null) {
            return null;
        }
        String stk = StkPhoneNormalizer.normalize(blank);
        if (stk != null) {
            return stk;
        }
        // Keep digits if not a full KE MSISDN yet — uniqueness still uses last-9 when possible.
        String digits = blank.replaceAll("[^0-9+]", "").trim();
        return digits.isBlank() ? blank : digits;
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
            String payoutTillNumber,
            String payoutPaybillNumber,
            String payoutPaybillAccount,
            String kopokopoRecipientUrl
    ) {
        if (payoutType != null) {
            String t = blankToNull(payoutType);
            if (t == null) {
                s.setPayoutType(SupplierPayoutTypes.MANUAL);
            } else {
                String norm = t.toLowerCase();
                if (!SupplierPayoutTypes.isValid(norm)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "payoutType must be manual, mobile_wallet, till, or paybill");
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
        if (payoutTillNumber != null) {
            s.setPayoutTillNumber(normalizeShortcode(payoutTillNumber, "payoutTillNumber"));
        }
        if (payoutPaybillNumber != null) {
            s.setPayoutPaybillNumber(normalizeShortcode(payoutPaybillNumber, "payoutPaybillNumber"));
        }
        if (payoutPaybillAccount != null) {
            String account = blankToNull(payoutPaybillAccount);
            s.setPayoutPaybillAccount(account);
        }
        if (kopokopoRecipientUrl != null) {
            s.setKopokopoExternalRecipientUrl(blankToNull(kopokopoRecipientUrl));
        }

        // Clear destination fields that don't apply to the active type.
        String type = s.getPayoutType() != null ? s.getPayoutType() : SupplierPayoutTypes.MANUAL;
        if (SupplierPayoutTypes.MANUAL.equals(type)) {
            s.setPayoutPhone(null);
            s.setPayoutTillNumber(null);
            s.setPayoutPaybillNumber(null);
            s.setPayoutPaybillAccount(null);
        } else if (SupplierPayoutTypes.MOBILE_WALLET.equals(type)) {
            s.setPayoutTillNumber(null);
            s.setPayoutPaybillNumber(null);
            s.setPayoutPaybillAccount(null);
            if (s.getPayoutPhone() == null || s.getPayoutPhone().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payoutPhone is required for mobile_wallet");
            }
        } else if (SupplierPayoutTypes.TILL.equals(type)) {
            s.setPayoutPhone(null);
            s.setPayoutPaybillNumber(null);
            s.setPayoutPaybillAccount(null);
            if (s.getPayoutTillNumber() == null || s.getPayoutTillNumber().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payoutTillNumber is required for till");
            }
        } else if (SupplierPayoutTypes.PAYBILL.equals(type)) {
            s.setPayoutPhone(null);
            s.setPayoutTillNumber(null);
            if (s.getPayoutPaybillNumber() == null || s.getPayoutPaybillNumber().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payoutPaybillNumber is required for paybill");
            }
            if (s.getPayoutPaybillAccount() == null || s.getPayoutPaybillAccount().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payoutPaybillAccount is required for paybill");
            }
        }
    }

    /** Digits-only shortcode (till / paybill). Accepts common spaces and dashes. */
    private static String normalizeShortcode(String raw, String fieldLabel) {
        String trimmed = blankToNull(raw);
        if (trimmed == null) {
            return null;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isBlank() || digits.length() < 5 || digits.length() > 12) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldLabel + " must be a 5–12 digit shortcode");
        }
        return digits;
    }

    private SupplierResponse toResponse(Supplier s) {
        String supplierNumber = null;
        if (s.getMarketplaceSupplierId() != null && !s.getMarketplaceSupplierId().isBlank()) {
            supplierNumber = marketplaceSupplierRepository.findById(s.getMarketplaceSupplierId())
                    .map(m -> m.getSupplierNumber())
                    .orElse(null);
        }
        return new SupplierResponse(
                s.getId(),
                s.getName(),
                s.getCode(),
                s.getSupplierType(),
                s.getVatPin(),
                s.isTaxExempt(),
                s.getCreditTermsDays(),
                s.getCreditLimit(),
                s.getPrepaymentBalance() != null ? s.getPrepaymentBalance() : BigDecimal.ZERO,
                s.getRating(),
                s.getStatus(),
                s.getNotes(),
                s.getPaymentMethodPreferred(),
                s.getPaymentDetails(),
                s.getPayoutType(),
                s.getPayoutPhone(),
                s.getPayoutTillNumber(),
                s.getPayoutPaybillNumber(),
                s.getPayoutPaybillAccount(),
                s.getKopokopoExternalRecipientUrl(),
                s.getMarketplaceSupplierId(),
                supplierNumber,
                s.getVersion(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getDeletedAt()
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
        snapshot.put("payoutTillNumber", s.getPayoutTillNumber());
        snapshot.put("payoutPaybillNumber", s.getPayoutPaybillNumber());
        snapshot.put("payoutPaybillAccount", s.getPayoutPaybillAccount());
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
