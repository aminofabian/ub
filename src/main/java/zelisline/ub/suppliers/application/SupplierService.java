package zelisline.ub.suppliers.application;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.api.dto.CreateSupplierContactRequest;
import zelisline.ub.suppliers.api.dto.CreateSupplierRequest;
import zelisline.ub.suppliers.api.dto.PatchSupplierContactRequest;
import zelisline.ub.suppliers.api.dto.PatchSupplierRequest;
import zelisline.ub.suppliers.api.dto.SupplierContactResponse;
import zelisline.ub.suppliers.api.dto.SupplierResponse;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;

    @Transactional(readOnly = true)
    public Page<SupplierResponse> listSuppliers(String businessId, Pageable pageable) {
        return supplierRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId, pageable)
                .map(SupplierService::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(String businessId, String supplierId) {
        Supplier s = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        return toResponse(s);
    }

    @Transactional
    public SupplierResponse createSupplier(String businessId, CreateSupplierRequest request) {
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
        try {
            supplierRepository.save(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use", ex);
        }
        return toResponse(s);
    }

    @Transactional
    public SupplierResponse patchSupplier(String businessId, String supplierId, PatchSupplierRequest patch) {
        Supplier s = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
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
        try {
            supplierRepository.save(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier code already in use", ex);
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
        assertSupplierInBusiness(businessId, supplierId);
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
        return toContactResponse(c);
    }

    @Transactional
    public SupplierContactResponse patchContact(
            String businessId,
            String supplierId,
            String contactId,
            PatchSupplierContactRequest patch
    ) {
        assertSupplierInBusiness(businessId, supplierId);
        SupplierContact c = supplierContactRepository.findByIdAndSupplierId(contactId, supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
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
        return toContactResponse(c);
    }

    @Transactional
    public void deleteContact(String businessId, String supplierId, String contactId) {
        assertSupplierInBusiness(businessId, supplierId);
        SupplierContact c = supplierContactRepository.findByIdAndSupplierId(contactId, supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
        supplierContactRepository.delete(c);
    }

    private void assertSupplierInBusiness(String businessId, String supplierId) {
        supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
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
}
