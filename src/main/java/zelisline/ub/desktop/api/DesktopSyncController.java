package zelisline.ub.desktop.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.desktop.application.DesktopSyncIngestService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Master-data export consumed by the desktop "connect" flow
 * (see {@code DesktopConnectService}).
 *
 * <p>Authenticated like any other API call: the caller must hold a valid JWT
 * for the business, and the tenant is resolved from the standard
 * {@code X-Tenant-Id} / host resolution — so a desktop client that signs in
 * with the shop owner's credentials can pull the snapshot with the same
 * headers the web app uses.
 */
@RestController
@RequestMapping("/api/v1/desktop/sync")
@RequiredArgsConstructor
public class DesktopSyncController {

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final TaxRateRepository taxRateRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DesktopSyncIngestService ingestService;

    @GetMapping("/master-data")
    public MasterDataSnapshot masterData(HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Business business = businessRepository
            .findByIdAndDeletedAtIsNull(businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Business not found"
                )
            );

        List<Item> items = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        List<String> itemIds = items.stream().map(Item::getId).toList();
        List<ItemImage> images = itemIds.isEmpty()
            ? List.of()
            : itemImageRepository.findByItemIdIn(
                itemIds,
                Sort.by("itemId").and(Sort.by("sortOrder")).and(Sort.by("id"))
            );

        return new MasterDataSnapshot(
            new MasterDataSnapshot.BusinessData(
                business.getId(),
                business.getName(),
                business.getSlug(),
                business.getCurrency(),
                business.getCountryCode(),
                business.getTimezone(),
                business.getSettings()
            ),
            branchRepository
                .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)
                .stream()
                .map(DesktopSyncController::toBranch)
                .toList(),
            categoryRepository
                .findByBusinessIdOrderByPositionAsc(businessId)
                .stream()
                .map(DesktopSyncController::toCategory)
                .toList(),
            items.stream().map(DesktopSyncController::toItem).toList(),
            taxRateRepository
                .findByBusinessIdAndActiveIsTrueOrderByNameAsc(businessId)
                .stream()
                .map(DesktopSyncController::toTaxRate)
                .toList(),
            userRepository
                .findByBusinessIdAndDeletedAtIsNull(businessId)
                .stream()
                .map(this::toStaff)
                .toList(),
            images.stream().map(DesktopSyncController::toImage).toList()
        );
    }

    private static MasterDataSnapshot.BranchData toBranch(Branch b) {
        return new MasterDataSnapshot.BranchData(
            b.getId(),
            b.getName(),
            b.getAddress(),
            b.getReceiptSettings(),
            b.isActive()
        );
    }

    private static MasterDataSnapshot.CategoryData toCategory(Category c) {
        return new MasterDataSnapshot.CategoryData(
            c.getId(),
            c.getName(),
            c.getSlug(),
            c.getDescription(),
            c.getParentId(),
            c.getPosition(),
            c.getDefaultTaxRateId(),
            c.getDefaultMarkupPct(),
            c.isActive()
        );
    }

    private static MasterDataSnapshot.ItemData toItem(Item i) {
        return new MasterDataSnapshot.ItemData(
            i.getId(),
            i.getSku(),
            i.getBarcode(),
            i.getPluCode(),
            i.getName(),
            i.getDescription(),
            i.getCategoryId(),
            i.getUnitType(),
            i.isStocked(),
            i.getCurrentStock(),
            i.getPackagingUnitName(),
            i.getPackagingUnitQty(),
            i.getBundlePrice(),
            i.getBuyingPrice(),
            i.getMinStockLevel(),
            i.getVariantOfItemId(),
            i.getVariantName(),
            i.isActive()
        );
    }

    private static MasterDataSnapshot.TaxRateData toTaxRate(TaxRate t) {
        return new MasterDataSnapshot.TaxRateData(
            t.getId(),
            t.getName(),
            t.getRatePercent(),
            t.isInclusive(),
            t.isActive()
        );
    }

    private MasterDataSnapshot.StaffData toStaff(User u) {
        String roleKey = u.getRoleId() == null
            ? null
            : roleRepository.findByIdAndDeletedAtIsNull(u.getRoleId())
                .map(zelisline.ub.identity.domain.Role::getRoleKey)
                .orElse(null);
        return new MasterDataSnapshot.StaffData(
            u.getId(),
            u.getBranchId(),
            u.getName(),
            u.getEmail(),
            u.getPhone(),
            u.getStatus(),
            roleKey
        );
    }

    private static MasterDataSnapshot.ImageData toImage(ItemImage img) {
        return new MasterDataSnapshot.ImageData(
            img.getId(),
            img.getItemId(),
            img.getContentType(),
            img.getSortOrder(),
            img.getFormat(),
            img.getSecureUrl(),
            img.getAltText(),
            img.getWidth(),
            img.getHeight(),
            img.getBytes()
        );
    }

    /**
     * Ingest till-uploaded shifts (the "up" direction of sync). Idempotent —
     * see {@link DesktopSyncIngestService}.
     */
    @PostMapping("/shifts")
    public ShiftSyncAck ingestShifts(
            @Valid @RequestBody ShiftSyncRequest request,
            HttpServletRequest http) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        return ingestService.ingest(businessId, request);
    }
}
