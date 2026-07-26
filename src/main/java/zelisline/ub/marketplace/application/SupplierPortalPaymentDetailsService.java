package zelisline.ub.marketplace.application;

import java.util.List;

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
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalPaymentDetailsRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalPaymentDetailsResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalPaymentDetailsService {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public SupplierPortalPaymentDetailsResponse get(String marketplaceSupplierId) {
        MarketplaceSupplier m = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        return toResponse(m, settings.isAllowPaymentDetailEdits());
    }

    @Transactional
    public SupplierPortalPaymentDetailsResponse patch(
            String marketplaceSupplierId,
            String supplierUserId,
            PatchSupplierPortalPaymentDetailsRequest body
    ) {
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        if (!settings.isAllowPaymentDetailEdits()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payment detail edits are disabled");
        }
        MarketplaceSupplier m = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        if (body.businessLegalName() != null) {
            m.setBusinessLegalName(blankToNull(body.businessLegalName()));
        }
        if (body.paybill() != null) {
            m.setPaybill(blankToNull(body.paybill()));
        }
        if (body.tillNumber() != null) {
            m.setTillNumber(blankToNull(body.tillNumber()));
        }
        if (body.bankName() != null) {
            m.setBankName(blankToNull(body.bankName()));
        }
        if (body.bankBranch() != null) {
            m.setBankBranch(blankToNull(body.bankBranch()));
        }
        if (body.bankAccountNumber() != null) {
            m.setBankAccountNumber(blankToNull(body.bankAccountNumber()));
        }
        if (body.bankAccountName() != null) {
            m.setBankAccountName(blankToNull(body.bankAccountName()));
        }
        if (body.mobileMoney() != null) {
            m.setMobileMoney(blankToNull(body.mobileMoney()));
        }
        if (body.preferredPaymentMethod() != null) {
            m.setPreferredPaymentMethod(blankToNull(body.preferredPaymentMethod()));
        }
        if (body.taxPin() != null) {
            m.setTaxPin(blankToNull(body.taxPin()));
        }
        if (body.vatNumber() != null) {
            m.setVatNumber(blankToNull(body.vatNumber()));
        }
        if (body.contactPerson() != null) {
            m.setContactPerson(blankToNull(body.contactPerson()));
        }
        if (body.phone() != null) {
            String phone = blankToNull(body.phone());
            if (phone != null) {
                String normalized = StkPhoneNormalizer.normalize(phone);
                m.setContactPhone(normalized != null ? normalized : phone);
            } else {
                m.setContactPhone(null);
            }
        }
        if (body.email() != null) {
            String email = blankToNull(body.email());
            m.setContactEmail(email == null ? null : email.toLowerCase());
        }
        marketplaceSupplierRepository.save(m);
        syncToLinkedLocals(m);
        publishUpdated(marketplaceSupplierId, supplierUserId, body);
        return toResponse(m, true);
    }

    /** Field names only — bank/till values are sensitive and stay out of the audit log. */
    private void publishUpdated(
            String marketplaceSupplierId,
            String supplierUserId,
            PatchSupplierPortalPaymentDetailsRequest body
    ) {
        List<String> fields = new java.util.ArrayList<>();
        addIfSet(fields, "businessLegalName", body.businessLegalName());
        addIfSet(fields, "paybill", body.paybill());
        addIfSet(fields, "tillNumber", body.tillNumber());
        addIfSet(fields, "bankName", body.bankName());
        addIfSet(fields, "bankBranch", body.bankBranch());
        addIfSet(fields, "bankAccountNumber", body.bankAccountNumber());
        addIfSet(fields, "bankAccountName", body.bankAccountName());
        addIfSet(fields, "mobileMoney", body.mobileMoney());
        addIfSet(fields, "preferredPaymentMethod", body.preferredPaymentMethod());
        addIfSet(fields, "taxPin", body.taxPin());
        addIfSet(fields, "vatNumber", body.vatNumber());
        addIfSet(fields, "contactPerson", body.contactPerson());
        addIfSet(fields, "phone", body.phone());
        addIfSet(fields, "email", body.email());
        try {
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SUPPLIERS,
                            AuditEventTypes.SUPPLIER_PORTAL_PAYMENT_DETAILS_UPDATED,
                            AuditEventSeverity.INFO)
                    .actor(supplierUserId, AuditEventActorType.USER)
                    .target("marketplace_supplier", marketplaceSupplierId)
                    .source("supplier_portal")
                    .diff(java.util.Map.of("fields", fields))
                    .build());
        } catch (RuntimeException ignored) {
            // Never fail the edit because of an audit write failure.
        }
    }

    private static void addIfSet(List<String> fields, String name, String value) {
        if (value != null) {
            fields.add(name);
        }
    }

    private void syncToLinkedLocals(MarketplaceSupplier m) {
        List<BusinessSupplierConnection> links = connectionRepository.findByMarketplaceSupplierIdAndStatus(
                m.getId(), BusinessSupplierConnectionStatuses.ACTIVE);
        for (BusinessSupplierConnection link : links) {
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(link.getLocalSupplierId()).orElse(null);
            if (local == null) {
                continue;
            }
            if (m.getPreferredPaymentMethod() != null) {
                local.setPaymentMethodPreferred(m.getPreferredPaymentMethod());
            }
            if (m.getMobileMoney() != null || m.getPaybill() != null || m.getTillNumber() != null) {
                String details = buildPaymentDetailsText(m);
                if (details != null) {
                    local.setPaymentDetails(details);
                }
            }
            if (m.getMobileMoney() != null) {
                local.setPayoutPhone(m.getMobileMoney());
            } else if (m.getContactPhone() != null) {
                local.setPayoutPhone(m.getContactPhone());
            }
            if (m.getTaxPin() != null) {
                local.setVatPin(m.getTaxPin());
            }
            supplierRepository.save(local);
        }
    }

    private static String buildPaymentDetailsText(MarketplaceSupplier m) {
        StringBuilder sb = new StringBuilder();
        append(sb, "Paybill", m.getPaybill());
        append(sb, "Till", m.getTillNumber());
        append(sb, "Mobile money", m.getMobileMoney());
        append(sb, "Bank", m.getBankName());
        append(sb, "Branch", m.getBankBranch());
        append(sb, "Account", m.getBankAccountNumber());
        append(sb, "Account name", m.getBankAccountName());
        return sb.isEmpty() ? null : sb.toString().trim();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(label).append(": ").append(value.trim());
    }

    private static SupplierPortalPaymentDetailsResponse toResponse(MarketplaceSupplier m, boolean editable) {
        return new SupplierPortalPaymentDetailsResponse(
                m.getId(),
                m.getBusinessLegalName(),
                m.getPaybill(),
                m.getTillNumber(),
                m.getBankName(),
                m.getBankBranch(),
                m.getBankAccountNumber(),
                m.getBankAccountName(),
                m.getMobileMoney(),
                m.getPreferredPaymentMethod(),
                m.getTaxPin(),
                m.getVatNumber(),
                m.getContactPerson(),
                m.getContactPhone(),
                m.getContactEmail(),
                editable);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
