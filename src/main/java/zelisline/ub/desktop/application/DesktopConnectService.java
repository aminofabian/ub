package zelisline.ub.desktop.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.desktop.api.dto.DesktopConnectRequest;
import zelisline.ub.desktop.api.dto.DesktopConnectResponse;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.identity.api.dto.LoginRequest;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.sales.api.dto.PostOpenShiftRequest;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.sales.api.dto.ShiftResponse;
import zelisline.ub.sales.application.ShiftService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * "Sign in with my online shop" — seeds a fresh desktop install from an
 * existing cloud business instead of creating a brand-new one
 * (DESKTOP_INSTALLATION.md §9b).
 *
 * <p>Flow: authenticate to the cloud with the owner's credentials, pull the
 * master-data snapshot ({@code GET /api/v1/desktop/sync/master-data}), then
 * seed the local MariaDB with the same entity IDs so future sync runs can
 * upsert idempotently. Writes the {@code .initialized} marker last, so the
 * install flips to the normal staff-login flow only after the seed completes.
 *
 * <p>Credentials are never stored: the password is used once for the cloud
 * login, and the local owner account is created with a locally-hashed copy.
 * A small {@code conf/cloud-sync.json} mapping (origin + cloud business id)
 * is written for future incremental sync runs.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopConnectService {

    private static final Logger log = LoggerFactory.getLogger(DesktopConnectService.class);

    /** Default hardware tier for connect installs (single till, full). */
    private static final String DEFAULT_HARDWARE_TIER = "B";

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final TaxRateRepository taxRateRepository;
    private final PasswordEncoder passwordEncoder;
    private final CatalogBootstrapService catalogBootstrapService;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final ShiftService shiftService;
    private final DesktopInitializationService initializationService;
    private final DesktopStaffSyncService staffSyncService;
    private final DesktopMediaSyncService mediaSyncService;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final TransactionTemplate transactionTemplate;
    private final CloudSyncSession cloudSyncSession;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public DesktopConnectResponse connect(DesktopConnectRequest request) {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        if (localId.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "app.desktop.business-id is not configured — set APP_DESKTOP_BUSINESS_ID before connecting"
            );
        }
        if (businessRepository.findByIdAndDeletedAtIsNull(localId).isPresent()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Desktop install is already set up"
            );
        }

        String origin = request.normalizedOrigin();
        log.info("[DesktopConnect] authenticating against {} for {}", origin, request.email());

        RestClient client = RestClient.builder().baseUrl(origin).build();

        // 0. Resolve the shop id from the email first. The cloud treats its
        // platform apex hosts (e.g. palmart.co.ke, kiosk.zelisline.com) as
        // tenant-less, so every call — including login — must carry the
        // X-Tenant-Id header to be routed to the right tenant.
        String resolvedBusinessId = resolveBusinessIdByEmail(client, request.email());

        // 1. Authenticate to the cloud with the existing shop credentials.
        //    - X-Tenant-Id: routes the login to the tenant (platform apex).
        //    - X-Kiosk-Client: native — keeps the access token in the JSON body
        //      (the Next.js proxy redacts it for browser clients).
        //    - The refresh token may only exist as the httpOnly `ub.refresh`
        //      cookie (cookie-mode auth), so read it from Set-Cookie.
        LoginResponse login;
        String refreshToken;
        try {
            ResponseEntity<LoginResponse> response = client
                .post()
                .uri("/api/v1/auth/login")
                .header("X-Tenant-Id", resolvedBusinessId)
                .header("X-Kiosk-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest(request.email(), request.password()))
                .retrieve()
                .toEntity(LoginResponse.class);
            login = response.getBody();
            refreshToken = login == null ? null : login.refreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                refreshToken = extractRefreshCookie(response.getHeaders());
            }
        } catch (Exception e) {
            log.warn("[DesktopConnect] cloud login failed: {}", e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Could not sign in to the online shop — check your email and password"
            );
        }
        if (login == null || login.accessToken() == null || login.accessToken().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Could not sign in to the online shop — check your email and password"
            );
        }
        String cloudBusinessId = login.user() == null ? null : login.user().businessId();
        if (cloudBusinessId == null || cloudBusinessId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Signed in, but no shop is linked to this account"
            );
        }
        String cloudOwnerName = login.user() == null || login.user().name() == null
            ? request.email()
            : login.user().name();
        String cloudOwnerUserId = login.user() == null ? null : login.user().id();

        // 2. Pull the master-data snapshot.
        MasterDataSnapshot snapshot;
        try {
            snapshot = client
                .get()
                .uri("/api/v1/desktop/sync/master-data")
                .header("Authorization", "Bearer " + login.accessToken())
                .header("X-Tenant-Id", cloudBusinessId)
                .retrieve()
                .body(MasterDataSnapshot.class);
        } catch (Exception e) {
            log.warn("[DesktopConnect] snapshot pull failed: {}", e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Connected, but could not download the shop data (" + e.getMessage() + ")"
            );
        }
        if (snapshot == null || snapshot.business() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty snapshot"
            );
        }

        // 3. Seed the local MariaDB from the snapshot (atomic — the marker is
        // only written after every row is in place).
        SeedResult seeded = transactionTemplate.execute(status -> {
            try {
                return seed(localId, snapshot, request, cloudOwnerName, cloudOwnerUserId);
            } catch (RuntimeException e) {
                status.setRollbackOnly();
                throw e;
            }
        });
        if (seeded == null) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Seeding failed — nothing was written"
            );
        }

        // 4. Cloud mapping for future incremental sync runs. The mirrored
        // staff ids are recorded so pushed sales attribute to the real cashier.
        java.util.List<String> staffIds = snapshot.staff() == null
            ? java.util.List.of()
            : snapshot.staff().stream()
                .map(MasterDataSnapshot.StaffData::id)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
        cloudSyncSession.persist(
            origin,
            cloudBusinessId,
            login.accessToken(),
            refreshToken,
            login.user() == null ? null : login.user().id(),
            staffIds
        );

        // 5. Product photos are downloaded in the background after the session
        // is persisted, so connect returns fast even with thousands of images.
        // Rows keep their cloud URL until each local copy lands; a failed
        // photo is non-fatal, and the next Sync now re-downloads any gaps.
        mediaSyncService.rehostAsync(localId, seeded.pendingImages());

        log.info(
            "[DesktopConnect] connected business={} from cloud business={} ({} items, {} categories)",
            localId,
            cloudBusinessId,
            snapshot.items().size(),
            snapshot.categories().size()
        );

        return new DesktopConnectResponse(
            localId,
            seeded.branchId(),
            "Connected to your online shop"
        );
    }

    /**
     * Re-authenticate an already-connected install when the stored cloud
     * session has expired. Unlike {@link #connect(DesktopConnectRequest)} this
     * does NOT re-seed the local DB — it only refreshes the cloud tokens in
     * {@code cloud-sync.json} so the next Sync now works again.
     */
    public DesktopConnectResponse reconnect(DesktopConnectRequest request) {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        if (localId.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "app.desktop.business-id is not configured — set APP_DESKTOP_BUSINESS_ID before reconnecting"
            );
        }
        if (businessRepository.findByIdAndDeletedAtIsNull(localId).isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This PC has not been set up yet — open Setup to connect first"
            );
        }

        String origin = request.normalizedOrigin();
        RestClient client = RestClient.builder().baseUrl(origin).build();
        String resolvedBusinessId = resolveBusinessIdByEmail(client, request.email());

        LoginResponse login;
        String refreshToken;
        try {
            ResponseEntity<LoginResponse> response = client
                .post()
                .uri("/api/v1/auth/login")
                .header("X-Tenant-Id", resolvedBusinessId)
                .header("X-Kiosk-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest(request.email(), request.password()))
                .retrieve()
                .toEntity(LoginResponse.class);
            login = response.getBody();
            refreshToken = login == null ? null : login.refreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                refreshToken = extractRefreshCookie(response.getHeaders());
            }
        } catch (Exception e) {
            log.warn("[DesktopConnect] cloud reconnect login failed: {}", e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Could not sign in to the online shop — check your email and password"
            );
        }
        if (login == null || login.accessToken() == null || login.accessToken().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Could not sign in to the online shop — check your email and password"
            );
        }
        String cloudBusinessId = login.user() == null ? null : login.user().businessId();
        if (cloudBusinessId == null || cloudBusinessId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Signed in, but no shop is linked to this account"
            );
        }

        // Keep the staff-id mapping from the previous session so push
        // attribution stays intact across a reconnect.
        List<String> staffIds = cloudSyncSession
            .load()
            .map(CloudSyncSession.Session::staffIds)
            .orElse(List.of());
        cloudSyncSession.persist(
            origin,
            cloudBusinessId,
            login.accessToken(),
            refreshToken,
            login.user() == null ? null : login.user().id(),
            staffIds
        );
        log.info("[DesktopConnect] reconnected business={} to cloud business={}", localId, cloudBusinessId);
        return new DesktopConnectResponse(localId, null, "Reconnected to your online shop");
    }

    private SeedResult seed(
            String localId,
            MasterDataSnapshot snapshot,
            DesktopConnectRequest request,
            String cloudOwnerName,
            String cloudOwnerUserId) {
        MasterDataSnapshot.BusinessData cloud = snapshot.business();

        Role ownerRole = roleRepository
            .findSystemRoleByKey(IdentityService.OWNER_ROLE_KEY)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Owner role is not configured — Flyway migrations may not have run"
                )
            );

        // ── Business ───────────────────────────────────────────────────
        Business business = new Business();
        business.setId(localId);
        business.setName(cloud.name() == null ? "My Shop" : cloud.name().trim());
        business.setSlug(cloud.slug() == null ? "desktop" : cloud.slug());
        business.setCurrency(cloud.currency() == null ? "KES" : cloud.currency());
        business.setCountryCode(cloud.countryCode() == null ? "KE" : cloud.countryCode());
        business.setTimezone(cloud.timezone() == null ? "Africa/Nairobi" : cloud.timezone());
        business.setSubscriptionTier("desktop");
        business.setSettings(cloud.settings() == null || cloud.settings().isBlank()
            ? "{}"
            : cloud.settings());
        businessRepository.save(business);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(localId);
        ledgerBootstrapService.ensureStandardAccounts(localId);

        // ── Branches ───────────────────────────────────────────────────
        Branch firstBranch = null;
        for (MasterDataSnapshot.BranchData b : snapshot.branches()) {
            Branch branch = new Branch();
            branch.setId(b.id());
            branch.setBusinessId(localId);
            branch.setName(b.name());
            branch.setAddress(b.address());
            branch.setReceiptSettings(b.receiptSettings());
            branch.setActive(b.active());
            branchRepository.save(branch);
            if (firstBranch == null) {
                firstBranch = branch;
            }
        }
        if (firstBranch == null) {
            firstBranch = new Branch();
            firstBranch.setBusinessId(localId);
            firstBranch.setName("Main Branch");
            firstBranch.setActive(true);
            firstBranch = branchRepository.save(firstBranch);
        }

        // ── Tax rates ──────────────────────────────────────────────────
        for (MasterDataSnapshot.TaxRateData t : snapshot.taxRates()) {
            TaxRate tax = new TaxRate();
            tax.setId(t.id());
            tax.setBusinessId(localId);
            tax.setName(t.name());
            tax.setRatePercent(t.ratePercent());
            tax.setInclusive(t.inclusive());
            tax.setActive(t.active());
            taxRateRepository.save(tax);
        }

        // ── Categories (same ids — ids are stable across sync runs) ────
        // Parent links are deferred: the snapshot is ordered by `position`,
        // which does NOT guarantee a parent precedes its child, and the local
        // FK `fk_categories_parent` rejects an insert whose parent is absent.
        List<MasterDataSnapshot.CategoryData> categoryParents = new java.util.ArrayList<>();
        for (MasterDataSnapshot.CategoryData c : snapshot.categories()) {
            Category category = new Category();
            category.setId(c.id());
            category.setBusinessId(localId);
            category.setName(c.name());
            category.setSlug(c.slug() == null || c.slug().isBlank()
                ? slugify(c.name())
                : c.slug());
            category.setDescription(c.description());
            category.setParentId(null);
            category.setPosition(c.position());
            category.setDefaultTaxRateId(c.defaultTaxRateId());
            category.setDefaultMarkupPct(c.defaultMarkupPct());
            category.setActive(c.active());
            categoryRepository.save(category);
            if (c.parentId() != null && !c.parentId().isBlank()) {
                categoryParents.add(c);
            }
        }
        for (MasterDataSnapshot.CategoryData c : categoryParents) {
            categoryRepository.findByIdAndBusinessId(c.parentId(), localId)
                .ifPresent(parent -> categoryRepository
                    .findByIdAndBusinessId(c.id(), localId)
                    .ifPresent(category -> {
                        category.setParentId(parent.getId());
                        categoryRepository.save(category);
                    }));
        }

        // ── Item types (items.item_type_id is NOT NULL + FK-bound) ─────
        // Null-tolerant: a cloud that hasn't deployed the item-types field
        // sends no list; the fallback then resolves the local default.
        java.util.List<MasterDataSnapshot.ItemTypeData> itemTypes =
            snapshot.itemTypes() == null ? List.of() : snapshot.itemTypes();
        java.util.Map<String, String> itemTypeIds = new java.util.HashMap<>();
        for (MasterDataSnapshot.ItemTypeData t : itemTypes) {
            if (t.id() == null || t.id().isBlank()) {
                continue;
            }
            ItemType itemType = new ItemType();
            itemType.setId(t.id());
            itemType.setBusinessId(localId);
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
        // Fallback for items whose type is missing from the snapshot: the
        // seeded local default (or the first synced type).
        String fallbackItemTypeId = itemTypeIds.isEmpty()
            ? itemTypeRepository.findByBusinessIdAndIsDefaultTrue(localId)
                .map(ItemType::getId)
                .orElseGet(() -> itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(localId)
                    .stream().map(ItemType::getId).findFirst().orElse(null))
            : itemTypeIds.values().iterator().next();

        // ── Items ──────────────────────────────────────────────────────
        // Variant links are deferred: a variant may precede its parent in the
        // snapshot, and a parent soft-deleted on the cloud may be missing
        // entirely (the local FK would reject it). Phase 2 links only variants
        // whose parent actually landed locally.
        List<MasterDataSnapshot.ItemData> variantLinks = new java.util.ArrayList<>();
        for (MasterDataSnapshot.ItemData i : snapshot.items()) {
            Item item = new Item();
            item.setId(i.id());
            item.setBusinessId(localId);
            item.setSku(i.sku());
            item.setBarcode(i.barcode());
            item.setPluCode(i.pluCode());
            item.setName(i.name());
            item.setDescription(i.description());
            item.setCategoryId(i.categoryId());
            item.setUnitType(i.unitType() == null ? "each" : i.unitType());
            item.setStocked(i.stocked());
            item.setCurrentStock(i.currentStock() == null ? BigDecimal.ZERO : i.currentStock());
            item.setPackagingUnitName(i.packagingUnitName());
            item.setPackagingUnitQty(i.packagingUnitQty());
            item.setBundlePrice(i.bundlePrice());
            item.setBuyingPrice(i.buyingPrice());
            item.setMinStockLevel(i.minStockLevel());
            item.setVariantOfItemId(null);
            item.setVariantName(i.variantName());
            item.setActive(i.active());
            String typeId = i.itemTypeId();
            item.setItemTypeId(typeId != null && itemTypeIds.containsKey(typeId)
                ? typeId
                : fallbackItemTypeId);
            itemRepository.save(item);
            if (i.variantOfItemId() != null && !i.variantOfItemId().isBlank()) {
                variantLinks.add(i);
            }
        }
        for (MasterDataSnapshot.ItemData i : variantLinks) {
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(i.variantOfItemId(), localId)
                .ifPresent(parent -> itemRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(i.id(), localId)
                    .ifPresent(item -> {
                        item.setVariantOfItemId(parent.getId());
                        itemRepository.save(item);
                    }));
        }

        // ── Owner user (credentials from the connect request) ──────────
        // The local owner row reuses the cloud owner id so sale attribution
        // stays consistent and the staff mirror (below) updates this row
        // instead of colliding on the unique (business_id, email) constraint.
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository
                .findByBusinessIdAndEmailAndDeletedAtIsNull(localId, email)
                .isPresent()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "An account with this email already exists for this business"
            );
        }
        User owner = new User();
        if (cloudOwnerUserId != null && !cloudOwnerUserId.isBlank()) {
            owner.setId(cloudOwnerUserId);
        }
        owner.setBusinessId(localId);
        owner.setEmail(email);
        owner.setName(cloudOwnerName.trim());
        owner.setPasswordHash(passwordEncoder.encode(request.password()));
        owner.setRoleId(ownerRole.getId());
        owner.setStatus(UserStatus.ACTIVE);
        User savedOwner = userRepository.save(owner);

        // ── Staff mirrors (cloud ids preserved; credentials NOT synced) ─
        // Only staff on branches present in the snapshot keep a branch id.
        java.util.Set<String> validBranchIds = snapshot.branches() == null
            ? java.util.Set.of()
            : snapshot.branches().stream()
                .map(MasterDataSnapshot.BranchData::id)
                .collect(java.util.stream.Collectors.toSet());
        staffSyncService.upsertStaff(localId, snapshot.staff(), validBranchIds);

        // ── Supplier directory (cloud ids preserved; stamped synced so the
        // first push doesn't bounce the whole directory back up) ─────
        if (snapshot.suppliers() != null) {
            for (MasterDataSnapshot.SupplierData d : snapshot.suppliers()) {
                if (d.id() == null || d.id().isBlank()) {
                    continue;
                }
                Supplier supplier = supplierRepository
                    .findByIdAndBusinessId(d.id(), localId)
                    .orElseGet(() -> {
                        Supplier created = new Supplier();
                        created.setId(d.id());
                        created.setBusinessId(localId);
                        return created;
                    });
                supplier.setName(d.name());
                supplier.setCode(d.code());
                supplier.setSupplierType(d.supplierType() == null ? "distributor" : d.supplierType());
                supplier.setVatPin(d.vatPin());
                supplier.setTaxExempt(d.taxExempt());
                supplier.setCreditTermsDays(d.creditTermsDays());
                supplier.setCreditLimit(d.creditLimit());
                supplier.setStatus(d.status() == null || d.status().isBlank() ? "active" : d.status());
                supplier.setNotes(d.notes());
                supplier.setPaymentMethodPreferred(d.paymentMethodPreferred());
                supplier.setPaymentDetails(d.paymentDetails());
                supplier.setPayoutType(d.payoutType() == null ? "manual" : d.payoutType());
                supplier.setPayoutPhone(d.payoutPhone());
                supplier.setPayoutTillNumber(d.payoutTillNumber());
                supplier.setPayoutPaybillNumber(d.payoutPaybillNumber());
                supplier.setPayoutPaybillAccount(d.payoutPaybillAccount());
                if (d.prepaymentBalance() != null) {
                    supplier.setPrepaymentBalance(d.prepaymentBalance());
                }
                supplier.setCloudSyncedAt(Instant.now());
                supplierRepository.save(supplier);

                if (d.contacts() != null) {
                    supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())
                        .forEach(supplierContactRepository::delete);
                    for (MasterDataSnapshot.SupplierContactData c : d.contacts()) {
                        SupplierContact contact = new SupplierContact();
                        contact.setId(c.id());
                        contact.setSupplierId(supplier.getId());
                        contact.setName(c.name());
                        contact.setRoleLabel(c.roleLabel());
                        contact.setPhone(c.phone());
                        contact.setEmail(c.email());
                        contact.setPrimaryContact(c.primary());
                        supplierContactRepository.save(contact);
                    }
                }
            }
        }

        // ── Image metadata (files re-hosted after the transaction) ─────
        List<DesktopMediaSyncService.PendingImage> pendingImages =
            mediaSyncService.upsertMetadata(localId, snapshot.images());

        // ── Starter shift (mirrors the create-shop wizard) ─────────────
        String shiftId = null;
        try {
            PostOpenShiftRequest shiftReq = new PostOpenShiftRequest(
                firstBranch.getId(),
                BigDecimal.ZERO, // opening cash — user counts later
                "Initial shift — opened by online-shop connect",
                Collections.emptyList()
            );
            ShiftResponse shift = shiftService.openShift(
                localId,
                shiftReq,
                savedOwner.getId()
            );
            shiftId = shift.id();
            log.info(
                "[DesktopConnect] opened starter shift={} on branch={}",
                shiftId,
                firstBranch.getId()
            );
        } catch (Exception e) {
            // Non-fatal: the owner can open a shift manually.
            log.warn("[DesktopConnect] could not open starter shift: {}", e.getMessage());
        }

        // ── Filesystem artefacts (marker written last) ─────────────────
        try {
            initializationService.completeInitialization(
                localId,
                DEFAULT_HARDWARE_TIER,
                Instant.now()
            );
        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Connected, but could not finalize the install: " + e.getMessage()
            );
        }

        return new SeedResult(firstBranch.getId(), pendingImages);
    }

    /**
     * The cloud exposes the tenant id for an email over a public endpoint, so
     * the till can attach {@code X-Tenant-Id} to its login call. Works for
     * both the Next.js proxy (palmart.co.ke) and the direct API base
     * (kiosk.zelisline.com).
     */
    private String resolveBusinessIdByEmail(RestClient client, String email) {
        try {
            var response = client
                .get()
                .uri(uri -> uri
                    .path("/api/v1/public/host/resolve-by-email")
                    .queryParam("email", email)
                    .build())
                .retrieve()
                .body(PublicHostResolveResponse.class);
            if (response == null || response.tenantId() == null || response.tenantId().isBlank()) {
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No online shop is linked to this account"
                );
            }
            return response.tenantId();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Could not reach your online shop (" + e.getMessage() + ")"
            );
        }
    }

    /** Extract the {@code ub.refresh} token from a Set-Cookie header (cookie-mode auth). */
    private static String extractRefreshCookie(org.springframework.http.HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        List<String> setCookies = headers.get(org.springframework.http.HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        String prefix = zelisline.ub.identity.application.RefreshTokenCookieSupport.COOKIE_NAME + "=";
        for (String cookie : setCookies) {
            String first = cookie.trim();
            int semi = first.indexOf(';');
            if (semi >= 0) {
                first = first.substring(0, semi);
            }
            if (first.startsWith(prefix)) {
                return first.substring(prefix.length());
            }
        }
        return null;
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

    private record SeedResult(String branchId, List<DesktopMediaSyncService.PendingImage> pendingImages) {}
}
