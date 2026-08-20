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
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
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

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final TaxRateRepository taxRateRepository;
    private final DesktopStaffSyncService staffSyncService;
    private final DesktopMediaSyncService mediaSyncService;
    private final CloudSyncSession cloudSyncSession;
    private final TransactionTemplate transactionTemplate;

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

        RestClient client = RestClient.builder().baseUrl(mapping.origin()).build();
        SnapshotFetch fetch = fetchSnapshot(client, mapping);
        MasterDataSnapshot snapshot = fetch.snapshot();

        UpsertOutcome outcome = transactionTemplate.execute(status -> upsert(localId, snapshot));
        if (outcome == null) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Sync failed — nothing was written"
            );
        }

        // Re-host product photos after the transaction (network I/O must not
        // hold the DB transaction open) and remember the mirrored staff ids so
        // pushed sales can be attributed to the real cashier.
        mediaSyncService.rehost(localId, outcome.pendingImages());
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

        int categories = 0;
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
        }

        int items = 0;
        for (MasterDataSnapshot.ItemData d : snapshot.items()) {
            Item item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(d.id(), localId)
                .orElseGet(() -> {
                    Item created = new Item();
                    created.setId(d.id());
                    created.setBusinessId(localId);
                    return created;
                });
            applyItem(item, d);
            itemRepository.save(item);
            items++;
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
        c.setParentId(d.parentId());
        c.setPosition(d.position());
        c.setDefaultTaxRateId(d.defaultTaxRateId());
        c.setDefaultMarkupPct(d.defaultMarkupPct());
        c.setActive(d.active());
    }

    private static void applyItem(Item i, MasterDataSnapshot.ItemData d) {
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
        i.setVariantOfItemId(d.variantOfItemId());
        i.setVariantName(d.variantName());
        i.setActive(d.active());
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
