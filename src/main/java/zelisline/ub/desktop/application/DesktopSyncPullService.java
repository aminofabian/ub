package zelisline.ub.desktop.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
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
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SaleLineKinds;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Re-pull of the online shop's master data into a connected desktop install —
 * the "down" direction of sync, run by the Settings → Sync now action.
 *
 * <p>Idempotent upsert by the shared entity ids: rows that already exist are
 * updated (so price/catalog changes from the cloud reach the till), new rows
 * are inserted. Deletions are intentionally NOT propagated in v1 — the till
 * may still hold local sales referencing items the cloud retired.
 *
 * <p>Auth: uses the stored cloud session, refreshing the token once when it
 * has expired (see {@link CloudSyncSession}).
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSyncPullService {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncPullService.class);

    /** Page size used when downloading cloud sales (matches the controller). */
    private static final int SALES_PAGE_SIZE = 500;

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final TaxRateRepository taxRateRepository;
    private final DesktopStaffSyncService staffSyncService;
    private final DesktopMediaSyncService mediaSyncService;
    private final CloudSyncSession cloudSyncSession;
    private final TransactionTemplate transactionTemplate;
    private final DesktopSyncProgressService syncProgress;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public record PullResult(
        int branches,
        int categories,
        int items,
        int taxRates,
        int staff,
        int images
    ) {}

    public PullResult pullMasterData() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession.load().orElse(null);
        if (mapping == null || localId.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This PC is not connected to an online shop yet"
            );
        }

        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        syncProgress.downloadStarted();
        SnapshotFetch fetch = fetchSnapshot(client, mapping);
        MasterDataSnapshot snapshot = fetch.snapshot();
        syncProgress.applyStarted(snapshot.items() == null ? 0 : snapshot.items().size());

        UpsertOutcome outcome = transactionTemplate.execute(status -> upsert(localId, snapshot));
        if (outcome == null) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Sync failed — nothing was written"
            );
        }

        // Re-host product photos in the background (network I/O must not hold
        // the transaction OR the HTTP request open) and remember the mirrored
        // staff ids so pushed sales can be attributed to the real cashier.
        mediaSyncService.rehostAsync(localId, outcome.pendingImages());
        cloudSyncSession.persistStaffIds(fetch.session(), outcome.staffIds());

        PullResult result = outcome.result();
        log.info(
            "[DesktopSync] pull refresh: {} branch(es), {} category(ies), {} item(s), {} tax rate(s), {} staff, {} image(s)",
            result.branches(),
            result.categories(),
            result.items(),
            result.taxRates(),
            result.staff(),
            result.images()
        );
        return result;
    }

    /**
     * Pull sales made on the cloud (web POS / other tills) into this till —
     * the "down" direction for sales, so every sale shows up at the till and in
     * its local reports regardless of where it was made.
     *
     * <p>Incremental via {@code lastSalesPullAt} (stored in cloud-sync.json) and
     * idempotent by sale id: sales already present locally (including this
     * till's own uploads) are skipped. Pulled sales are stamped
     * {@code cloud_synced_at} so they are never pushed back up.
     *
     * @return number of new sales mirrored into the local database
     */
    public int pullCloudSales() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession.load().orElse(null);
        if (mapping == null || localId.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This PC is not connected to an online shop yet"
            );
        }

        java.time.Instant cursor = mapping.lastSalesPullAt() != null
            ? mapping.lastSalesPullAt()
            : java.time.Instant.EPOCH;
        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();

        // A till that was offline for a while can have many pages of cloud sales
        // to mirror; keep pulling until a page comes back short (the cursor
        // advances per page, so an interrupted run resumes where it left off).
        int total = 0;
        while (true) {
            CloudSalesSnapshot snapshot = fetchSales(client, mapping, cursor);
            List<CloudSalesSnapshot.CloudSaleData> sales = snapshot.sales();
            if (sales == null || sales.isEmpty()) {
                break;
            }

            Integer inserted = transactionTemplate.execute(status ->
                upsertCloudSales(localId, snapshot));
            if (inserted == null) {
                throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Sales pull failed — nothing was written"
                );
            }
            total += inserted;

            // Advance the cursor to the newest sale we've seen (>= semantics +
            // the idempotent skip make re-pulls safe).
            java.time.Instant newest = sales.stream()
                .map(CloudSalesSnapshot.CloudSaleData::soldAt)
                .filter(java.util.Objects::nonNull)
                .max(java.time.Instant::compareTo)
                .orElse(null);
            if (newest != null && newest.isAfter(cursor)) {
                cursor = newest;
                cloudSyncSession.persistLastSalesPullAt(mapping, cursor);
            }

            if (sales.size() < SALES_PAGE_SIZE) {
                break;
            }
        }
        log.info(
            "[DesktopSync] sales pull: {} new sale(s) from {} (cursor now {})",
            total, mapping.origin(), cursor
        );
        return total;
    }

    private CloudSalesSnapshot fetchSales(
            RestClient client,
            CloudSyncSession.Session mapping,
            java.time.Instant since) {
        try {
            return doFetchSales(client, mapping, since);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download sales (" + e.getMessage() + ")"
                );
            }
            CloudSyncSession.Session refreshed = cloudSyncSession
                .refresh(client, mapping)
                .orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired — open Settings → Sync to reconnect"
                );
            }
            try {
                return doFetchSales(client, refreshed, since);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download sales (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private CloudSalesSnapshot doFetchSales(
            RestClient client,
            CloudSyncSession.Session mapping,
            java.time.Instant since) {
        CloudSalesSnapshot snapshot = client
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/v1/desktop/sync/sales")
                .queryParam("since", since.toString())
                .build())
            .header("Authorization", "Bearer " + mapping.accessToken())
            .header("X-Tenant-Id", mapping.cloudBusinessId())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(CloudSalesSnapshot.class);
        if (snapshot == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty sales snapshot"
            );
        }
        return snapshot;
    }

    /**
     * Insert cloud sales that don't exist locally yet. Runs in the caller's
     * transaction. Returns the number of newly inserted sales.
     */
    private Integer upsertCloudSales(String localId, CloudSalesSnapshot snapshot) {
        String fallbackUser = userRepository
            .findIdsByBusinessIdAndDeletedAtIsNull(localId)
            .stream()
            .findFirst()
            .orElse(null);
        // Mirror the cloud's customer directory (phones + live credit state)
        // before the sales that reference it, so sales.customer_id resolves.
        // Stamped cloud_synced_at so an untouched mirror is never pushed back;
        // the next local edit (balance change from a till sale) re-pushes it.
        if (snapshot.customers() != null) {
            for (CloudSalesSnapshot.CloudCustomerData customerData : snapshot.customers()) {
                upsertCloudCustomer(localId, customerData);
            }
        }
        int inserted = 0;
        for (CloudSalesSnapshot.CloudSaleData data : snapshot.sales()) {
            if (saleRepository.findByIdAndBusinessId(data.id(), localId).isPresent()) {
                continue;
            }
            insertCloudSale(localId, data, fallbackUser);
            inserted++;
        }
        return inserted;
    }

    /** Upsert a cloud customer (phones replaced wholesale; balance adopted as-is). */
    private void upsertCloudCustomer(String localId, CloudSalesSnapshot.CloudCustomerData data) {
        // Mirror is change-aware: an unchanged copy is left untouched (no
        // updated_at / cloud_synced_at bump, no phone delete+reinsert), so the
        // 2-minute pull doesn't rewrite the whole directory — and an untouched
        // mirror is never re-pushed (updated_at stays <= cloud_synced_at).
        java.util.Optional<Customer> existing = customerRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(data.id(), localId);
        Customer customer = existing.orElseGet(() -> {
            Customer c = new Customer();
            c.setId(data.id());
            c.setBusinessId(localId);
            return c;
        });
        boolean changed = existing.isEmpty()
            || !java.util.Objects.equals(customer.getName(), data.name())
            || !java.util.Objects.equals(customer.getEmail(), data.email())
            || !java.util.Objects.equals(customer.getNotes(), data.notes());
        if (changed) {
            customer.setName(data.name());
            customer.setEmail(data.email());
            customer.setNotes(data.notes());
            // Flush BEFORE any child row (phones / credit account) is queued —
            // Hibernate does not order unassociated inserts, and the FK on
            // credit_accounts.customer_id would otherwise fire first.
            customer.setCloudSyncedAt(java.time.Instant.now());
            customerRepository.saveAndFlush(customer);
        }

        boolean phonesChanged = data.phones() != null && phonesDiffer(customer.getId(), data.phones());
        if (phonesChanged) {
            customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId())
                .forEach(p -> customerPhoneRepository.delete(p));
            for (CloudSalesSnapshot.CloudCustomerPhoneData phoneData : data.phones()) {
                if (customerPhoneRepository.existsByBusinessIdAndPhone(localId, phoneData.phone())) {
                    continue;
                }
                CustomerPhone phone = new CustomerPhone();
                phone.setId(phoneData.id());
                phone.setBusinessId(localId);
                phone.setCustomerId(customer.getId());
                phone.setPhone(phoneData.phone());
                phone.setPrimary(phoneData.primary());
                customerPhoneRepository.save(phone);
            }
        }

        boolean balanceChanged = false;
        if (data.creditAccount() != null) {
            CreditAccount acc = creditAccountRepository
                .findByCustomerIdAndBusinessId(customer.getId(), localId)
                .orElseGet(() -> {
                    CreditAccount createdAcc = new CreditAccount();
                    createdAcc.setId(java.util.UUID.randomUUID().toString());
                    createdAcc.setBusinessId(localId);
                    createdAcc.setCustomerId(customer.getId());
                    return createdAcc;
                });
            // BigDecimal equality keeps an unchanged balance from dirtying the
            // row; only touch last_activity_at when the balance actually moved.
            BigDecimal incoming = data.creditAccount().balanceOwed() == null
                ? java.math.BigDecimal.ZERO
                : data.creditAccount().balanceOwed();
            if (incoming.compareTo(acc.getBalanceOwed()) != 0) {
                balanceChanged = true;
                acc.setBalanceOwed(incoming);
                acc.setLastActivityAt(java.time.Instant.now());
            }
            if (data.creditAccount().walletBalance() != null) {
                acc.setWalletBalance(data.creditAccount().walletBalance());
            }
            acc.setLoyaltyPoints(data.creditAccount().loyaltyPoints());
            acc.setCreditLimit(data.creditAccount().creditLimit());
            creditAccountRepository.save(acc);
        }

        // Advance the sync stamp whenever ANY part of the customer changed —
        // including balance/phones — so the dirty query below sees nothing to
        // re-push (a balance the till merely adopted from the cloud must not
        // bounce back, while a balance it changed itself stays dirty). The row
        // already exists (created/flushed above or in an earlier cycle), so a
        // plain save is enough here.
        if (!changed && (phonesChanged || balanceChanged)) {
            customer.setCloudSyncedAt(java.time.Instant.now());
            customerRepository.save(customer);
        }
    }

    private boolean phonesDiffer(
            String customerId,
            List<CloudSalesSnapshot.CloudCustomerPhoneData> incoming) {
        List<CustomerPhone> existing = customerPhoneRepository
            .findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (existing.size() != incoming.size()) {
            return true;
        }
        java.util.Map<String, CloudSalesSnapshot.CloudCustomerPhoneData> byId = incoming.stream()
            .collect(java.util.stream.Collectors.toMap(
                CloudSalesSnapshot.CloudCustomerPhoneData::id, p -> p));
        for (CustomerPhone phone : existing) {
            CloudSalesSnapshot.CloudCustomerPhoneData match = byId.get(phone.getId());
            if (match == null
                || !java.util.Objects.equals(match.phone(), phone.getPhone())
                || match.primary() != phone.isPrimary()) {
                return true;
            }
        }
        return false;
    }

    private void insertCloudSale(
            String localId,
            CloudSalesSnapshot.CloudSaleData data,
            String fallbackUser) {
        // sales.branch_id / shifts.branch_id are NOT NULL FKs to branches the
        // till mirrors during master-data sync. A sale at a branch the till
        // hasn't seen yet fails loudly (the cursor doesn't advance, so it
        // retries after the next master-data refresh) instead of tripping an
        // obscure FK error that wedges the whole pull.
        if (branchRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(data.branchId(), localId)
            .isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A cloud sale (" + data.id() + ") is at a branch this till hasn't "
                    + "synced yet — run Settings → Sync now to refresh master data"
            );
        }

        // sales.sold_by / shifts.opened_by are NOT NULL FKs to local users.
        // Cloud users are mirrored by the staff sync; if one isn't mirrored yet
        // (new hire after the last master pull), attribute to any local user so
        // the sale still records. Customers/ledger refs are not synced in v1.
        String soldBy = (data.soldBy() != null
            && userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(
                data.soldBy(), localId).isPresent())
            ? data.soldBy()
            : fallbackUser;
        if (soldBy == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This till has no local staff user to attribute cloud sales to "
                    + "— run Settings → Sync now to refresh staff"
            );
        }

        // The sales.shift_id FK requires the shift to exist — create a closed
        // placeholder for remote shifts. It is stamped cloud_synced_at so the
        // push side never uploads it (its zero cash figures must not overwrite
        // the cloud's real shift totals).
        Shift shift = shiftRepository
            .findByIdAndBusinessId(data.shiftId(), localId)
            .orElseGet(() -> {
                Shift created = new Shift();
                created.setId(data.shiftId());
                created.setBusinessId(localId);
                created.setBranchId(data.branchId());
                created.setOpenedBy(soldBy);
                created.setStatus(SalesConstants.SHIFT_STATUS_CLOSED);
                created.setOpeningCash(java.math.BigDecimal.ZERO);
                created.setExpectedClosingCash(java.math.BigDecimal.ZERO);
                created.setOpenedAt(data.shiftOpenedAt() != null
                    ? data.shiftOpenedAt()
                    : data.soldAt() != null ? data.soldAt() : java.time.Instant.now());
                created.setClosedAt(data.soldAt());
                created.setCloudSyncedAt(java.time.Instant.now());
                return created;
            });
        shiftRepository.save(shift);

        Sale sale = new Sale();
        sale.setId(data.id());
        sale.setBusinessId(localId);
        sale.setBranchId(data.branchId());
        sale.setShiftId(data.shiftId());
        sale.setStatus(data.status());
        sale.setIdempotencyKey(data.idempotencyKey());
        sale.setGrandTotal(data.grandTotal());
        sale.setCashReceived(data.cashReceived());
        // Keep the cloud receipt number so the local next-receipt sequence
        // stays aligned — unless another local sale already holds it (the till
        // and the cloud allocate MAX+1 independently), in which case leave it
        // blank rather than trip the unique key and roll back the whole batch.
        Long receiptNo = data.receiptNo();
        if (receiptNo != null
            && saleRepository.existsByBusinessIdAndReceiptNo(localId, receiptNo)) {
            log.warn(
                "[DesktopSync] cloud sale {} reuses local receipt {} — leaving receipt blank",
                data.id(), receiptNo
            );
            receiptNo = null;
        }
        sale.setReceiptNo(receiptNo);
        sale.setSoldBy(soldBy);
        // Customer was just mirrored from the same snapshot, so the FK resolves;
        // old sales referencing a customer the cloud has since deleted drop it.
        if (data.customerId() != null
            && customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(
                data.customerId(), localId).isPresent()) {
            sale.setCustomerId(data.customerId());
        }
        sale.setSoldAt(data.soldAt() == null ? java.time.Instant.now() : data.soldAt());
        sale.setVoidedAt(data.voidedAt());
        sale.setVoidNotes(data.voidNotes());
        sale.setRefundedTotal(data.refundedTotal() == null
            ? java.math.BigDecimal.ZERO
            : data.refundedTotal());
        sale.setCloudSyncedAt(java.time.Instant.now());
        saleRepository.save(sale);

        if (data.items() != null) {
            for (CloudSalesSnapshot.CloudSaleItemData itemData : data.items()) {
                // Lines referencing items this till hasn't mirrored yet are
                // skipped (the sale row itself keeps the correct total).
                if (itemData.itemId() == null
                    || itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(
                        itemData.itemId(), localId).isEmpty()) {
                    log.debug("[DesktopSync] skipping line {} of sale {} (item not on this till)",
                        itemData.lineIndex(), data.id());
                    continue;
                }
                SaleItem item = new SaleItem();
                item.setId(itemData.id());
                item.setSaleId(sale.getId());
                item.setLineIndex(itemData.lineIndex());
                item.setLineKind(itemData.lineKind() == null
                    ? SaleLineKinds.ITEM
                    : itemData.lineKind());
                item.setLineLabel(itemData.lineLabel());
                item.setItemId(itemData.itemId());
                item.setBatchId(null);
                item.setQuantity(itemData.quantity());
                item.setUnitPrice(itemData.unitPrice());
                item.setLineTotal(itemData.lineTotal());
                item.setUnitCost(itemData.unitCost());
                item.setCostTotal(itemData.costTotal());
                item.setProfit(itemData.profit());
                item.setRegularUnitPrice(itemData.regularUnitPrice());
                item.setDiscountAmount(itemData.discountAmount());
                item.setDiscountId(itemData.discountId());
                item.setDiscountName(itemData.discountName());
                saleItemRepository.save(item);
            }
        }

        if (data.payments() != null) {
            for (CloudSalesSnapshot.CloudSalePaymentData paymentData : data.payments()) {
                SalePayment payment = new SalePayment();
                payment.setId(paymentData.id());
                payment.setSaleId(sale.getId());
                payment.setMethod(paymentData.method());
                payment.setAmount(paymentData.amount());
                payment.setReference(paymentData.reference());
                payment.setSortOrder(paymentData.sortOrder());
                salePaymentRepository.save(payment);
            }
        }
    }

    private record SnapshotFetch(MasterDataSnapshot snapshot, CloudSyncSession.Session session) {}

    private SnapshotFetch fetchSnapshot(
            RestClient client,
            CloudSyncSession.Session mapping) {
        try {
            return new SnapshotFetch(doFetch(client, mapping), mapping);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download shop data (" + e.getMessage() + ")"
                );
            }
            CloudSyncSession.Session refreshed = cloudSyncSession
                .refresh(client, mapping)
                .orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired — open Settings → Sync to reconnect"
                );
            }
            try {
                return new SnapshotFetch(doFetch(client, refreshed), refreshed);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download shop data (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private MasterDataSnapshot doFetch(
            RestClient client,
            CloudSyncSession.Session mapping) {
        MasterDataSnapshot snapshot = client
            .get()
            .uri("/api/v1/desktop/sync/master-data")
            .header("Authorization", "Bearer " + mapping.accessToken())
            .header("X-Tenant-Id", mapping.cloudBusinessId())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(MasterDataSnapshot.class);
        if (snapshot == null || snapshot.business() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty snapshot"
            );
        }
        return snapshot;
    }

    private record UpsertOutcome(
        PullResult result,
        List<DesktopMediaSyncService.PendingImage> pendingImages,
        List<String> staffIds
    ) {}

    private UpsertOutcome upsert(String localId, MasterDataSnapshot snapshot) {
        // Business row must exist (created at connect); refresh its settings.
        businessRepository
            .findByIdAndDeletedAtIsNull(localId)
            .ifPresentOrElse(
                b -> applyBusiness(b, snapshot.business()),
                () -> {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Local shop record is missing — reconnect instead"
                    );
                }
            );

        int branches = 0;
        for (MasterDataSnapshot.BranchData d : snapshot.branches()) {
            Branch branch = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(d.id(), localId)
                .orElseGet(() -> {
                    Branch created = new Branch();
                    created.setId(d.id());
                    created.setBusinessId(localId);
                    return created;
                });
            applyBranch(branch, d);
            branchRepository.save(branch);
            branches++;
        }

        // Categories — parent links deferred (position ordering does not
        // guarantee a parent precedes its child; the local FK rejects an
        // insert whose parent is absent).
        int categories = 0;
        List<MasterDataSnapshot.CategoryData> categoryParents = new ArrayList<>();
        for (MasterDataSnapshot.CategoryData d : snapshot.categories()) {
            Category category = categoryRepository
                .findByIdAndBusinessId(d.id(), localId)
                .orElseGet(() -> {
                    Category created = new Category();
                    created.setId(d.id());
                    created.setBusinessId(localId);
                    return created;
                });
            applyCategory(category, d);
            categoryRepository.save(category);
            categories++;
            if (d.parentId() != null && !d.parentId().isBlank()) {
                categoryParents.add(d);
            }
        }
        for (MasterDataSnapshot.CategoryData d : categoryParents) {
            categoryRepository.findByIdAndBusinessId(d.parentId(), localId)
                .ifPresent(parent -> categoryRepository
                    .findByIdAndBusinessId(d.id(), localId)
                    .ifPresent(category -> {
                        category.setParentId(parent.getId());
                        categoryRepository.save(category);
                    }));
        }

        // Item types (items.item_type_id is NOT NULL + FK-bound).
        // Null-tolerant: a cloud that hasn't deployed the item-types field
        // sends no list; the fallback then resolves the local default.
        java.util.List<MasterDataSnapshot.ItemTypeData> itemTypes =
            snapshot.itemTypes() == null ? List.of() : snapshot.itemTypes();
        java.util.Map<String, String> itemTypeIds = new java.util.HashMap<>();
        for (MasterDataSnapshot.ItemTypeData t : itemTypes) {
            if (t.id() == null || t.id().isBlank()) {
                continue;
            }
            ItemType itemType = itemTypeRepository
                .findByIdAndBusinessId(t.id(), localId)
                .orElseGet(() -> {
                    ItemType created = new ItemType();
                    created.setId(t.id());
                    created.setBusinessId(localId);
                    return created;
                });
            itemType.setTypeKey(t.typeKey());
            itemType.setLabel(t.label());
            itemType.setIcon(t.icon());
            itemType.setColor(t.color());
            itemType.setSortOrder(t.sortOrder());
            itemType.setActive(t.active());
            itemType.setDefault(t.isDefault());
            itemTypeRepository.save(itemType);
            itemTypeIds.put(t.id(), t.id());
        }
        String fallbackItemTypeId = itemTypeIds.isEmpty()
            ? itemTypeRepository.findByBusinessIdAndIsDefaultTrue(localId)
                .map(ItemType::getId)
                .orElseGet(() -> itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(localId)
                    .stream().map(ItemType::getId).findFirst().orElse(null))
            : itemTypeIds.values().iterator().next();

        // Items — variant links deferred: a variant may precede its parent in
        // the snapshot, or the parent may be missing (soft-deleted on the
        // cloud), either of which the local FK rejects. Phase 2 links only
        // variants whose parent landed locally.
        int items = 0;
        List<MasterDataSnapshot.ItemData> variantLinks = new ArrayList<>();
        for (MasterDataSnapshot.ItemData d : snapshot.items()) {
            Item item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(d.id(), localId)
                .orElseGet(() -> {
                    Item created = new Item();
                    created.setId(d.id());
                    created.setBusinessId(localId);
                    return created;
                });
            applyItem(item, d, fallbackItemTypeId, itemTypeIds);
            itemRepository.save(item);
            items++;
            syncProgress.applyProgress(items);
            if (d.variantOfItemId() != null && !d.variantOfItemId().isBlank()) {
                variantLinks.add(d);
            }
        }
        for (MasterDataSnapshot.ItemData d : variantLinks) {
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(d.variantOfItemId(), localId)
                .ifPresent(parent -> itemRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(d.id(), localId)
                    .ifPresent(item -> {
                        item.setVariantOfItemId(parent.getId());
                        itemRepository.save(item);
                    }));
        }

        int taxRates = 0;
        for (MasterDataSnapshot.TaxRateData d : snapshot.taxRates()) {
            TaxRate taxRate = taxRateRepository
                .findByIdAndBusinessId(d.id(), localId)
                .orElseGet(() -> {
                    TaxRate created = new TaxRate();
                    created.setId(d.id());
                    created.setBusinessId(localId);
                    return created;
                });
            applyTaxRate(taxRate, d);
            taxRateRepository.save(taxRate);
            taxRates++;
        }

        // Staff mirrors (same ids as the cloud → push attribution by id).
        // Only staff on branches present in the snapshot keep a branch id.
        List<MasterDataSnapshot.StaffData> staff = snapshot.staff();
        java.util.Set<String> validBranchIds = snapshot.branches() == null
            ? java.util.Set.of()
            : snapshot.branches().stream()
                .map(MasterDataSnapshot.BranchData::id)
                .collect(java.util.stream.Collectors.toSet());
        int staffCount = staffSyncService.upsertStaff(localId, staff, validBranchIds);
        // Clean up any buyer (storefront customer) rows a pre-fix sync already
        // mirrored — they are not till staff.
        staffSyncService.removeBuyerStaff(localId);
        List<String> staffIds = new ArrayList<>();
        if (staff != null) {
            staff.forEach(d -> {
                if (d.id() != null && !d.id().isBlank()) {
                    staffIds.add(d.id());
                }
            });
        }

        // Image metadata (files re-hosted after the transaction).
        List<DesktopMediaSyncService.PendingImage> pending =
            mediaSyncService.upsertMetadata(localId, snapshot.images());

        return new UpsertOutcome(
            new PullResult(branches, categories, items, taxRates, staffCount, snapshot.images() == null ? 0 : snapshot.images().size()),
            pending,
            staffIds
        );
    }

    private static void applyBusiness(Business b, MasterDataSnapshot.BusinessData d) {
        b.setName(d.name() == null ? b.getName() : d.name().trim());
        b.setSlug(d.slug() == null ? b.getSlug() : d.slug());
        b.setCurrency(d.currency() == null ? b.getCurrency() : d.currency());
        b.setCountryCode(d.countryCode() == null ? b.getCountryCode() : d.countryCode());
        b.setTimezone(d.timezone() == null ? b.getTimezone() : d.timezone());
        if (d.settings() != null && !d.settings().isBlank()) {
            b.setSettings(d.settings());
        }
    }

    private static void applyBranch(Branch b, MasterDataSnapshot.BranchData d) {
        b.setName(d.name());
        b.setAddress(d.address());
        b.setReceiptSettings(d.receiptSettings());
        b.setActive(d.active());
    }

    private static void applyCategory(Category c, MasterDataSnapshot.CategoryData d) {
        c.setName(d.name());
        c.setSlug(d.slug() == null || d.slug().isBlank() ? slugify(d.name()) : d.slug());
        c.setDescription(d.description());
        // Parent link is set in the deferred pass (see upsert).
        c.setParentId(null);
        c.setPosition(d.position());
        c.setDefaultTaxRateId(d.defaultTaxRateId());
        c.setDefaultMarkupPct(d.defaultMarkupPct());
        c.setActive(d.active());
    }

    private static void applyItem(
            Item i,
            MasterDataSnapshot.ItemData d,
            String fallbackItemTypeId,
            java.util.Map<String, String> itemTypeIds) {
        i.setSku(d.sku());
        i.setBarcode(d.barcode());
        i.setPluCode(d.pluCode());
        i.setName(d.name());
        i.setDescription(d.description());
        i.setCategoryId(d.categoryId());
        i.setUnitType(d.unitType() == null ? "each" : d.unitType());
        i.setStocked(d.stocked());
        i.setCurrentStock(d.currentStock() == null ? BigDecimal.ZERO : d.currentStock());
        i.setPackagingUnitName(d.packagingUnitName());
        i.setPackagingUnitQty(d.packagingUnitQty());
        i.setBundlePrice(d.bundlePrice());
        i.setBuyingPrice(d.buyingPrice());
        i.setMinStockLevel(d.minStockLevel());
        // Variant parent is linked in phase 2 (after all items exist).
        i.setVariantOfItemId(null);
        i.setVariantName(d.variantName());
        i.setActive(d.active());
        String typeId = d.itemTypeId();
        i.setItemTypeId(typeId != null && itemTypeIds.containsKey(typeId)
            ? typeId
            : fallbackItemTypeId);
    }

    private static void applyTaxRate(TaxRate t, MasterDataSnapshot.TaxRateData d) {
        t.setName(d.name());
        t.setRatePercent(d.ratePercent());
        t.setInclusive(d.inclusive());
        t.setActive(d.active());
    }

    private static String slugify(String name) {
        if (name == null || name.isBlank()) {
            return "category";
        }
        return name.trim()
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    }

    private static boolean isUnauthorized(Exception e) {
        return e.getMessage() != null
            && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"));
    }
}
