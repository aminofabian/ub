package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.globalcatalog.domain.GlobalProductSupplierLink;
import zelisline.ub.globalcatalog.domain.GlobalSupplierTemplate;
import zelisline.ub.globalcatalog.repository.GlobalProductSupplierLinkRepository;
import zelisline.ub.globalcatalog.repository.GlobalSupplierTemplateRepository;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.application.SupplierProductPrimaryService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * On adopt, maps a global product's primary supplier template to a tenant supplier
 * with stable code {@code GC-{template.code}}.
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogSupplierAdoptLinker {

    public static final String TENANT_CODE_PREFIX = "GC-";

    private final GlobalProductSupplierLinkRepository linkRepository;
    private final GlobalSupplierTemplateRepository templateRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierProductPrimaryService primaryService;

    @Transactional
    public Optional<String> linkPrimaryTemplate(String businessId, String globalProductId, Item item) {
        if (item == null || item.getDeletedAt() != null || !item.isSellable() || !item.isStocked()) {
            return Optional.empty();
        }
        GlobalProductSupplierLink primary = linkRepository.findPrimaryByGlobalProductId(globalProductId)
                .orElse(null);
        if (primary == null) {
            return Optional.empty();
        }
        GlobalSupplierTemplate template = templateRepository.findById(primary.getGlobalSupplierTemplateId())
                .orElse(null);
        if (template == null) {
            return Optional.empty();
        }

        Supplier supplier = getOrCreateTenantSupplier(businessId, template);
        upsertPrimaryLink(item.getId(), supplier.getId(), primary);
        demoteSystemUnassigned(businessId, item.getId());
        primaryService.normalizeAfterChange(businessId, item.getId());
        return Optional.of(supplier.getId());
    }

    public static String tenantSupplierCode(String templateCode) {
        String raw = TENANT_CODE_PREFIX + templateCode.trim().toUpperCase(Locale.ROOT);
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }

    private Supplier getOrCreateTenantSupplier(String businessId, GlobalSupplierTemplate template) {
        String code = tenantSupplierCode(template.getCode());
        return supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, code)
                .orElseGet(() -> {
                    Supplier s = new Supplier();
                    s.setBusinessId(businessId);
                    s.setName(template.getName());
                    s.setCode(code);
                    s.setSupplierType(blankTo(template.getSupplierType(), "distributor"));
                    s.setVatPin(blankToNull(template.getVatPin()));
                    s.setStatus("active");
                    s.setNotes(blankToNull(template.getNotes()));
                    return supplierRepository.save(s);
                });
    }

    private void upsertPrimaryLink(
            String itemId,
            String supplierId,
            GlobalProductSupplierLink templateLink
    ) {
        SupplierProduct sp = supplierProductRepository.findBySupplierIdAndItemId(supplierId, itemId)
                .orElseGet(SupplierProduct::new);
        sp.setSupplierId(supplierId);
        sp.setItemId(itemId);
        sp.setDeletedAt(null);
        sp.setActive(true);
        sp.setPrimaryLink(true);
        if (blankToNull(templateLink.getSupplierSku()) != null) {
            sp.setSupplierSku(templateLink.getSupplierSku().trim());
        }
        BigDecimal cost = templateLink.getDefaultCostPrice();
        if (cost != null && cost.compareTo(BigDecimal.ZERO) > 0) {
            sp.setDefaultCostPrice(cost);
        }
        supplierProductRepository.save(sp);
    }

    private void demoteSystemUnassigned(String businessId, String itemId) {
        for (SupplierProduct link : supplierProductRepository.listForItem(businessId, itemId)) {
            Supplier supplier = supplierRepository.findById(link.getSupplierId()).orElse(null);
            if (supplier == null || !SupplierCodes.SYSTEM_UNASSIGNED.equals(supplier.getCode())) {
                continue;
            }
            link.setPrimaryLink(false);
            link.setActive(false);
            link.setDeletedAt(Instant.now());
            supplierProductRepository.save(link);
        }
    }

    private static String blankTo(String value, String fallback) {
        String trimmed = blankToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
