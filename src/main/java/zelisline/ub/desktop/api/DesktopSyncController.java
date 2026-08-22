package zelisline.ub.desktop.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.desktop.api.dto.CloudSalesSnapshot;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.desktop.application.DesktopSyncIngestService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
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

    /** Storefront customer roles that must not be mirrored onto the till. */
    private static final java.util.Set<String> NON_TILL_ROLE_KEYS = java.util.Set.of("buyer");

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final TaxRateRepository taxRateRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DesktopSyncIngestService ingestService;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;

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
            itemTypeRepository
                .findByBusinessIdOrderBySortOrderAsc(businessId)
                .stream()
                .map(DesktopSyncController::toItemType)
                .toList(),
            userRepository
                .findByBusinessIdAndDeletedAtIsNull(businessId)
                .stream()
                .map(this::toStaff)
                .filter(s -> s.roleKey() == null || !NON_TILL_ROLE_KEYS.contains(s.roleKey()))
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
            i.isActive(),
            i.getItemTypeId()
        );
    }

    private static MasterDataSnapshot.ItemTypeData toItemType(ItemType t) {
        return new MasterDataSnapshot.ItemTypeData(
            t.getId(),
            t.getTypeKey(),
            t.getLabel(),
            t.getIcon(),
            t.getColor(),
            t.getSortOrder(),
            t.isActive(),
            t.isDefault()
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
        if ((request.shifts() == null || request.shifts().isEmpty())
                && (request.customers() == null || request.customers().isEmpty())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch is empty — provide at least one shift or customer"
            );
        }
        return ingestService.ingest(businessId, request);
    }

    /**
     * Cloud → till sales pull (the "down" direction of sync): every sale made
     * at/after {@code since} (web POS, other tills), with items + payments, so
     * the till can mirror remote sales into its local database. Idempotent on
     * the till side (it skips ids it already has).
     */
    @GetMapping("/sales")
    public CloudSalesSnapshot cloudSales(
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") String since,
            HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Instant cursor = parseCursor(since);
        List<Sale> sales = saleRepository
            .findByBusinessIdAndSoldAtGreaterThanEqualOrderBySoldAtAsc(
                businessId, cursor, PageRequest.of(0, 500));

        // Batch-load shifts so each sale carries its shift's openedAt (the till
        // needs it to create the placeholder shift the FK requires).
        List<String> shiftIds = sales.stream().map(Sale::getShiftId).distinct().toList();
        Map<String, Shift> shifts = shiftIds.isEmpty()
            ? Map.of()
            : shiftRepository.findAllById(shiftIds).stream()
                .collect(Collectors.toMap(Shift::getId, s -> s));

        List<CloudSalesSnapshot.CloudSaleData> data = sales.stream()
            .map(s -> toCloudSale(s, shifts.get(s.getShiftId())))
            .toList();
        List<CloudSalesSnapshot.CloudCustomerData> customers = loadCloudCustomers(businessId);
        return new CloudSalesSnapshot(data, customers);
    }

    /** Live customer directory for the till: name/phones + current credit state. */
    private List<CloudSalesSnapshot.CloudCustomerData> loadCloudCustomers(String businessId) {
        List<Customer> customers = customerRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        List<String> ids = customers.stream().map(Customer::getId).toList();
        Map<String, List<CustomerPhone>> phones = ids.isEmpty()
            ? Map.of()
            : customerPhoneRepository.findByCustomerIdIn(ids).stream()
                .collect(Collectors.groupingBy(CustomerPhone::getCustomerId));
        Map<String, CreditAccount> credit = ids.isEmpty()
            ? Map.of()
            : creditAccountRepository.findByCustomerIdIn(ids).stream()
                .collect(Collectors.toMap(CreditAccount::getCustomerId, a -> a));
        return customers.stream()
            .map(c -> toCloudCustomer(c, phones.getOrDefault(c.getId(), List.of()), credit.get(c.getId())))
            .toList();
    }

    private static CloudSalesSnapshot.CloudCustomerData toCloudCustomer(
            Customer customer,
            List<CustomerPhone> phones,
            CreditAccount creditAccount) {
        List<CloudSalesSnapshot.CloudCustomerPhoneData> phoneData = phones.stream()
            .map(p -> new CloudSalesSnapshot.CloudCustomerPhoneData(
                p.getId(), p.getPhone(), p.isPrimary()))
            .toList();
        CloudSalesSnapshot.CloudCreditAccountData creditData = creditAccount == null
            ? null
            : new CloudSalesSnapshot.CloudCreditAccountData(
                creditAccount.getBalanceOwed(),
                creditAccount.getWalletBalance(),
                creditAccount.getLoyaltyPoints(),
                creditAccount.getCreditLimit());
        return new CloudSalesSnapshot.CloudCustomerData(
            customer.getId(),
            customer.getName(),
            customer.getEmail(),
            customer.getNotes(),
            phoneData,
            creditData
        );
    }

    private CloudSalesSnapshot.CloudSaleData toCloudSale(Sale sale, Shift shift) {
        List<CloudSalesSnapshot.CloudSaleItemData> items = saleItemRepository
            .findBySaleIdOrderByLineIndexAsc(sale.getId())
            .stream()
            .map(DesktopSyncController::toCloudItem)
            .toList();
        List<CloudSalesSnapshot.CloudSalePaymentData> payments = salePaymentRepository
            .findBySaleIdOrderBySortOrderAsc(sale.getId())
            .stream()
            .map(DesktopSyncController::toCloudPayment)
            .toList();
        return new CloudSalesSnapshot.CloudSaleData(
            sale.getId(),
            sale.getBranchId(),
            sale.getShiftId(),
            shift != null ? shift.getOpenedAt() : null,
            sale.getStatus(),
            sale.getIdempotencyKey(),
            sale.getGrandTotal(),
            sale.getCashReceived(),
            sale.getSoldBy(),
            sale.getCustomerId(),
            sale.getSoldAt(),
            sale.getVoidedAt(),
            sale.getVoidNotes(),
            sale.getRefundedTotal(),
            sale.getReceiptNo(),
            items,
            payments
        );
    }

    private static CloudSalesSnapshot.CloudSaleItemData toCloudItem(SaleItem item) {
        return new CloudSalesSnapshot.CloudSaleItemData(
            item.getId(),
            item.getLineIndex(),
            item.getLineKind(),
            item.getLineLabel(),
            item.getItemId(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getLineTotal(),
            item.getUnitCost(),
            item.getCostTotal(),
            item.getProfit(),
            item.getRegularUnitPrice(),
            item.getDiscountAmount(),
            item.getDiscountId(),
            item.getDiscountName()
        );
    }

    private static CloudSalesSnapshot.CloudSalePaymentData toCloudPayment(SalePayment payment) {
        return new CloudSalesSnapshot.CloudSalePaymentData(
            payment.getId(),
            payment.getMethod(),
            payment.getAmount(),
            payment.getReference(),
            payment.getSortOrder()
        );
    }

    private static Instant parseCursor(String since) {
        try {
            return Instant.parse(since.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "since must be an ISO-8601 instant (e.g. 2026-08-20T00:00:00Z)"
            );
        }
    }
}
