package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.MarketplaceConnectResponse;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Connects a business to a supplier discovered in the public directory
 * (another tenant's active supplier record) by copying a local supplier
 * and importing barcode-matched catalogue links.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceConnectService {

    private final PublicMarketplaceSearchService publicMarketplaceSearchService;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final ItemRepository itemRepository;
    private final SupplierIdentityIndexService supplierIdentityIndexService;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierPortalShopLinkService shopLinkService;

    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetail(String supplierId) {
        return publicMarketplaceSearchService.getSupplierDetail(supplierId);
    }

    @Transactional
    public MarketplaceConnectResponse connect(String businessId, String sourceSupplierId) {
        Supplier source = supplierRepository.findByIdAndDeletedAtIsNull(sourceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (!"active".equalsIgnoreCase(source.getStatus())
                || SupplierCodes.SYSTEM_UNASSIGNED.equals(source.getCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier is not available");
        }
        if (businessId.equals(source.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This supplier already belongs to your business");
        }
        if (supplierRepository.existsDuplicateName(businessId, source.getName(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A local supplier with this name already exists; open it or rename before connecting.");
        }

        SupplierContact primary = supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(source.getId())
                .stream()
                .findFirst()
                .orElse(null);

        Supplier local = new Supplier();
        local.setBusinessId(businessId);
        local.setName(source.getName());
        local.setSupplierType(source.getSupplierType() != null ? source.getSupplierType() : "distributor");
        local.setStatus("active");
        local.setVatPin(source.getVatPin());
        local.setTaxExempt(source.isTaxExempt());
        local.setNotes("Added from marketplace directory (source supplier " + source.getId() + ")");
        if (source.getMarketplaceSupplierId() != null && !source.getMarketplaceSupplierId().isBlank()) {
            local.setMarketplaceSupplierId(source.getMarketplaceSupplierId());
        }
        if (primary != null && primary.getPhone() != null && !primary.getPhone().isBlank()) {
            local.setPayoutPhone(primary.getPhone());
        }
        supplierRepository.save(local);
        supplierIdentityIndexService.upsertTenantSupplier(
                local,
                primary != null ? primary.getPhone() : null,
                primary != null ? primary.getEmail() : null);

        if (primary != null
                && ((primary.getName() != null && !primary.getName().isBlank())
                        || (primary.getPhone() != null && !primary.getPhone().isBlank())
                        || (primary.getEmail() != null && !primary.getEmail().isBlank()))) {
            SupplierContact contact = new SupplierContact();
            contact.setSupplierId(local.getId());
            contact.setName(primary.getName() != null && !primary.getName().isBlank()
                    ? primary.getName()
                    : source.getName());
            contact.setPhone(primary.getPhone());
            contact.setEmail(primary.getEmail());
            contact.setRoleLabel(primary.getRoleLabel());
            contact.setPrimaryContact(true);
            supplierContactRepository.save(contact);
        }

        int imported = importCatalogueLinks(businessId, local.getId(), source.getId());

        String connectionId = local.getId();
        if (local.getMarketplaceSupplierId() != null) {
            connectionId = ensurePortalConnection(businessId, local);
            try {
                shopLinkService.ensureLinksAndCatalogue(local.getMarketplaceSupplierId());
            } catch (RuntimeException ignored) {
                // Soft.
            }
        }

        return new MarketplaceConnectResponse(
                connectionId,
                local.getId(),
                source.getId(),
                local.getName(),
                imported,
                "active");
    }

    private String ensurePortalConnection(String businessId, Supplier local) {
        String marketplaceSupplierId = local.getMarketplaceSupplierId();
        var existing = connectionRepository.findByBusinessIdAndMarketplaceSupplierId(
                businessId, marketplaceSupplierId);
        if (existing.isPresent()) {
            BusinessSupplierConnection conn = existing.get();
            if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(conn.getStatus())) {
                conn.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
                connectionRepository.save(conn);
            }
            return conn.getId();
        }
        if (connectionRepository.existsByLocalSupplierId(local.getId())) {
            return connectionRepository.findByLocalSupplierId(local.getId())
                    .map(BusinessSupplierConnection::getId)
                    .orElse(local.getId());
        }
        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(businessId);
        connection.setMarketplaceSupplierId(marketplaceSupplierId);
        connection.setLocalSupplierId(local.getId());
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connection.setCanViewPurchaseHistory(true);
        connectionRepository.save(connection);
        return connection.getId();
    }

    private int importCatalogueLinks(String businessId, String localSupplierId, String sourceSupplierId) {
        List<SupplierProduct> sourceLinks =
                supplierProductRepository.listActivePublicForSupplier(sourceSupplierId);
        int imported = 0;
        for (SupplierProduct sourceLink : sourceLinks) {
            Item sourceItem = itemRepository.findById(sourceLink.getItemId()).orElse(null);
            if (sourceItem == null
                    || sourceItem.getDeletedAt() != null
                    || !sourceItem.isActive()
                    || sourceItem.getBarcode() == null
                    || sourceItem.getBarcode().isBlank()) {
                continue;
            }
            var localItem = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(
                    businessId, sourceItem.getBarcode().trim());
            if (localItem.isEmpty()) {
                continue;
            }
            String itemId = localItem.get().getId();
            SupplierProduct link = supplierProductRepository.findBySupplierIdAndItemId(localSupplierId, itemId)
                    .orElseGet(SupplierProduct::new);
            if (link.getId() != null && link.getDeletedAt() == null) {
                continue;
            }
            link.setSupplierId(localSupplierId);
            link.setItemId(itemId);
            link.setSupplierSku(sourceLink.getSupplierSku());
            link.setPackSize(sourceLink.getPackSize());
            link.setPackUnit(sourceLink.getPackUnit());
            link.setMinOrderQty(sourceLink.getMinOrderQty());
            link.setDefaultCostPrice(sourceLink.getDefaultCostPrice() != null
                    ? sourceLink.getDefaultCostPrice()
                    : sourceLink.getLastCostPrice());
            link.setActive(true);
            link.setDeletedAt(null);
            supplierProductRepository.save(link);
            imported++;
        }
        return imported;
    }
}
