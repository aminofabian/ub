package zelisline.ub.onboarding.progress.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.onboarding.progress.api.dto.SetupProgressResponse;
import zelisline.ub.onboarding.progress.api.dto.SetupProgressRewardDto;
import zelisline.ub.onboarding.progress.api.dto.SetupProgressStepDto;
import zelisline.ub.onboarding.progress.api.dto.SetupProgressSubMilestoneDto;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingGateService;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SetupProgressService {

    private static final Logger log = LoggerFactory.getLogger(SetupProgressService.class);

    private static final int DISMISS_MIN_PERCENT = 80;
    private static final int DEFAULT_SNOOZE_HOURS = 24;
    /** One-time SMS credit bonus when required setup steps complete. */
    static final int SETUP_COMPLETE_SMS_BONUS = 25;

    private final MerchantOnboardingGateService gateService;
    private final BusinessRepository businessRepository;
    private final BusinessOpsAlertSettingsService opsAlertSettingsService;
    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;
    private final SetupProgressSettingsService setupProgressSettingsService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<SmsCreditService> smsCreditService;
    /** Absent in a deployment without the service module; a service shop then gets the standard steps. */
    private final ObjectProvider<zelisline.ub.serviceops.application.ServiceSetupFacts> serviceSetupFacts;

    @Transactional
    public SetupProgressResponse getForBusiness(String businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        return build(business, Instant.now(), true);
    }

    @Transactional
    public SetupProgressResponse snooze(String businessId, Integer hours) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        int h = hours == null || hours < 1 ? DEFAULT_SNOOZE_HOURS : Math.min(hours, 168);
        Instant until = Instant.now().plusSeconds(h * 3600L);
        business.setSettings(setupProgressSettingsService.snooze(business.getSettings(), until));
        businessRepository.save(business);
        return build(business, Instant.now(), false);
    }

    @Transactional
    public SetupProgressResponse dismiss(String businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        SetupProgressResponse current = build(business, Instant.now(), false);
        if (current.percentComplete() < DISMISS_MIN_PERCENT && !current.shopReady()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Complete at least " + DISMISS_MIN_PERCENT + "% of setup before dismissing");
        }
        business.setSettings(setupProgressSettingsService.dismiss(business.getSettings(), Instant.now()));
        businessRepository.save(business);
        return build(business, Instant.now(), false);
    }

    private SetupProgressResponse build(Business business, Instant now, boolean mayGrantReward) {
        String businessId = business.getId();
        var snap = gateService.snapshot(businessId);
        SetupProgressSettingsService.SetupProgressPrefs prefs =
                setupProgressSettingsService.read(business.getSettings());
        boolean phoneVerified = opsAlertSettingsService.resolveForBusiness(businessId).hasVerifiedPhone();
        long supplierLinks = supplierProductRepository.countActiveLinksForBusiness(businessId);
        boolean hasVariant = itemRepository.existsActiveVariantByBusinessId(businessId);
        boolean storefrontEnabled = readStorefrontEnabled(business.getSettings());

        // A shop that only sells services has no shelf to stock and no supplier to link: asking for
        // them would leave its setup unfinishable. It gets steps about its menu and its team instead.
        zelisline.ub.serviceops.application.ServiceSetupFacts serviceFacts =
                isServiceOnly(business.getSettings()) ? serviceSetupFacts.getIfAvailable() : null;
        if (serviceFacts != null) {
            return buildForServiceShop(business, snap, prefs, phoneVerified, serviceFacts.facts(businessId), now, mayGrantReward);
        }

        boolean shopCreated = snap.questionnaireDone();
        boolean stocked = snap.sellableSkuCount() > 0;
        boolean supplierDone = snap.supplierCount() > 0 && supplierLinks > 0;
        boolean phoneDone = phoneVerified;
        boolean staffDone = snap.hasInvitedStaff();
        boolean saleDone = snap.hasCompletedSale();
        boolean liveDone = storefrontEnabled;

        List<StepDef> defs = List.of(
                stepShopCreated(shopCreated),
                stepStockShelf(snap, stocked, hasVariant),
                stepSupplierLoop(snap, supplierDone, supplierLinks, snap.hasPostedSupply()),
                stepPhoneVerified(phoneDone),
                stepInviteCashier(staffDone),
                stepFirstSale(saleDone),
                stepGoLive(liveDone));

        boolean shopReady = shopCreated && stocked && supplierDone && phoneDone && saleDone;
        return assemble(business, prefs, defs, shopCreated, shopReady, now, mayGrantReward);
    }

    /** The steps of a shop that only sells services: its menu, its team, its phone, a first payment, online booking. */
    private SetupProgressResponse buildForServiceShop(
            Business business,
            MerchantOnboardingGateService.Snapshot snap,
            SetupProgressSettingsService.SetupProgressPrefs prefs,
            boolean phoneVerified,
            zelisline.ub.serviceops.application.ServiceSetupFacts.Facts facts,
            Instant now,
            boolean mayGrantReward) {
        boolean shopCreated = snap.questionnaireDone();
        boolean menuDone = facts.pricedServices() > 0;
        boolean teamDone = facts.team() > 0;
        boolean saleDone = snap.hasCompletedSale();
        List<StepDef> defs = List.of(
                stepShopCreated(shopCreated),
                new StepDef("service_menu", "Put your services on the menu", true, menuDone, menuDone ? 10 : 0, 10,
                        "/services/setup", null, List.of(sub("service", "Add a service with a price", 10, menuDone))),
                new StepDef("service_team", "Add the people who do the work", true, teamDone, teamDone ? 10 : 0, 10,
                        "/commissions", null, List.of(sub("member", "Add a team member", 10, teamDone))),
                stepPhoneVerified(phoneVerified),
                new StepDef("first_sale", "Take your first payment", true, saleDone, saleDone ? 15 : 0, 15,
                        "/cashier?activate=first-sale", null, List.of(sub("sale", "Take payment for a service", 15, saleDone))),
                new StepDef("online_booking", "Let customers book online", false, facts.onlineBooking(),
                        facts.onlineBooking() ? 10 : 0, 10, "/services/appointments", null,
                        List.of(sub("hours", "Set your online booking hours", 10, facts.onlineBooking()))));
        boolean shopReady = shopCreated && menuDone && teamDone && phoneVerified && saleDone;
        return assemble(business, prefs, defs, shopCreated, shopReady, now, mayGrantReward);
    }

    private SetupProgressResponse assemble(
            Business business,
            SetupProgressSettingsService.SetupProgressPrefs prefs,
            List<StepDef> defs,
            boolean shopCreated,
            boolean shopReady,
            Instant now,
            boolean mayGrantReward) {
        int earnedPoints = defs.stream().mapToInt(StepDef::earnedPoints).sum();
        int maxPoints = defs.stream().mapToInt(StepDef::maxPoints).sum();
        int percent = maxPoints <= 0 ? 0 : Math.min(100, Math.round(earnedPoints * 100f / maxPoints));

        SetupProgressRewardDto reward = null;
        if (shopReady) {
            reward = maybeGrantReward(business, prefs, now, mayGrantReward);
            prefs = setupProgressSettingsService.read(business.getSettings());
        } else if (prefs.rewardGranted()) {
            reward = new SetupProgressRewardDto(
                    prefs.rewardSmsCredits() != null ? prefs.rewardSmsCredits() : SETUP_COMPLETE_SMS_BONUS,
                    prefs.rewardGrantedAt(),
                    false);
        }

        String currentKey = defs.stream()
                .filter(s -> !s.done())
                .map(StepDef::key)
                .findFirst()
                .orElse(null);

        List<SetupProgressStepDto> steps = new ArrayList<>();
        boolean foundCurrent = false;
        for (StepDef def : defs) {
            String status;
            if (def.done()) {
                status = "completed";
            } else if (!foundCurrent) {
                status = "current";
                foundCurrent = true;
            } else {
                status = "pending";
            }
            steps.add(new SetupProgressStepDto(
                    def.key(),
                    def.label(),
                    status,
                    def.required(),
                    def.earnedPoints(),
                    def.maxPoints(),
                    def.done() ? null : def.actionUrl(),
                    def.recommendedSubKey(),
                    def.subMilestones()));
        }

        boolean snoozed = prefs.snoozed(now);
        boolean dismissed = prefs.dismissedAt() != null && (percent >= DISMISS_MIN_PERCENT || shopReady);
        boolean visible = shopCreated && !shopReady && !snoozed && !dismissed;

        return new SetupProgressResponse(
                visible,
                percent,
                earnedPoints,
                maxPoints,
                shopReady,
                currentKey,
                prefs.snoozedUntil(),
                steps,
                reward);
    }

    private SetupProgressRewardDto maybeGrantReward(
            Business business,
            SetupProgressSettingsService.SetupProgressPrefs prefs,
            Instant now,
            boolean mayGrant) {
        if (prefs.rewardGranted()) {
            return new SetupProgressRewardDto(
                    prefs.rewardSmsCredits() != null ? prefs.rewardSmsCredits() : SETUP_COMPLETE_SMS_BONUS,
                    prefs.rewardGrantedAt(),
                    false);
        }
        if (!mayGrant) {
            return null;
        }
        SmsCreditService credits = smsCreditService.getIfAvailable();
        if (credits == null) {
            log.debug("SMS credit service unavailable; skipping setup reward businessId={}", business.getId());
            return null;
        }
        try {
            credits.grant(
                    business.getId(),
                    SETUP_COMPLETE_SMS_BONUS,
                    "setup_complete_bonus",
                    null);
            business.setSettings(setupProgressSettingsService.markRewardGranted(
                    business.getSettings(), now, SETUP_COMPLETE_SMS_BONUS));
            businessRepository.save(business);
            return new SetupProgressRewardDto(SETUP_COMPLETE_SMS_BONUS, now, true);
        } catch (RuntimeException ex) {
            log.warn("setup completion SMS grant failed businessId={}", business.getId(), ex);
            return null;
        }
    }

    /** Store formats as the shop's profile has them, or failing that as its onboarding answers do. */
    private boolean isServiceOnly(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            List<String> types = storeTypes(root.path("profile"));
            if (types.isEmpty()) {
                types = storeTypes(root.path("onboarding").path("answers"));
            }
            return zelisline.ub.tenancy.api.dto.StoreTypeCodes.isServiceOnlyBusiness(types);
        } catch (Exception ex) {
            return false;
        }
    }

    private static List<String> storeTypes(JsonNode node) {
        List<String> out = new ArrayList<>();
        JsonNode many = node.path("storeTypes");
        if (many.isArray()) {
            for (JsonNode type : many) {
                if (type.isTextual() && !type.asText().isBlank()) {
                    out.add(type.asText());
                }
            }
        }
        if (out.isEmpty() && node.path("storeType").isTextual() && !node.path("storeType").asText().isBlank()) {
            out.add(node.path("storeType").asText());
        }
        return out;
    }

    private boolean readStorefrontEnabled(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            JsonNode storefront = root.path("storefront");
            JsonNode enabled = storefront.get("enabled");
            if (enabled == null || enabled.isNull()) {
                return false;
            }
            if (enabled.isBoolean()) {
                return enabled.booleanValue();
            }
            if (enabled.isTextual()) {
                return Boolean.parseBoolean(enabled.asText().trim());
            }
            if (enabled.isNumber()) {
                return enabled.intValue() != 0;
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private static StepDef stepShopCreated(boolean done) {
        return new StepDef(
                "shop_created",
                "Shop created",
                true,
                done,
                done ? 10 : 0,
                10,
                null,
                null,
                List.of());
    }

    private static StepDef stepStockShelf(
            MerchantOnboardingGateService.Snapshot snap,
            boolean done,
            boolean hasVariant) {
        boolean catalog = snap.catalogImportCount() > 0;
        int earned = 0;
        String recommended = "quick";
        if (done) {
            if (catalog) {
                earned = 10;
                recommended = "catalog";
            } else if (hasVariant) {
                earned = 10;
                recommended = "variant";
            } else {
                earned = 8;
                recommended = "quick";
            }
        }
        List<SetupProgressSubMilestoneDto> subs = List.of(
                sub("quick", "Quick add one product", 8, done && earned == 8),
                sub("catalog", "Import from catalogue", 10, done && catalog),
                sub("variant", "Create a family + variant", 10, done && hasVariant && !catalog));
        return new StepDef(
                "stock_shelf",
                "Add your first product",
                true,
                done,
                earned,
                10,
                "/products/catalog?from=setup",
                recommended,
                subs);
    }

    private static StepDef stepSupplierLoop(
            MerchantOnboardingGateService.Snapshot snap,
            boolean done,
            long links,
            boolean postedSupply) {
        boolean hasSupplier = snap.supplierCount() > 0;
        boolean linked = links > 0;
        int earned = 0;
        if (hasSupplier) {
            earned += 5;
        }
        if (linked) {
            earned += 5;
        }
        if (postedSupply) {
            earned += 5;
        }
        List<SetupProgressSubMilestoneDto> subs = List.of(
                sub("create", "Create a supplier", 5, hasSupplier),
                sub("link", "Link a product to supplier", 5, linked),
                sub("supply", "Post your first supply", 5, postedSupply));
        return new StepDef(
                "supplier_loop",
                "Add a supplier & link a product",
                true,
                done,
                Math.min(earned, 15),
                15,
                "/suppliers",
                linked ? "link" : "create",
                subs);
    }

    private static StepDef stepPhoneVerified(boolean done) {
        return new StepDef(
                "phone_verified",
                "Add your shop phone number",
                true,
                done,
                done ? 10 : 0,
                10,
                "/business/settings",
                null,
                List.of(sub("verify", "Verify your phone number", 10, done)));
    }

    private static StepDef stepInviteCashier(boolean done) {
        return new StepDef(
                "invite_cashier",
                "Invite a cashier",
                false,
                done,
                done ? 8 : 0,
                8,
                "/users",
                null,
                List.of(sub("invite", "Add a cashier with till PIN", 8, done)));
    }

    private static StepDef stepFirstSale(boolean done) {
        return new StepDef(
                "first_sale",
                "Make your first sale",
                true,
                done,
                done ? 15 : 0,
                15,
                // FE deep-link: register till (if needed) + open-shift gate.
                "/cashier?activate=first-sale",
                null,
                List.of(sub("sale", "Complete a till sale", 15, done)));
    }

    private static StepDef stepGoLive(boolean done) {
        return new StepDef(
                "go_live",
                "Turn on your online shop",
                false,
                done,
                done ? 10 : 0,
                10,
                "/business/settings",
                null,
                List.of(sub("enable", "Enable your storefront", 10, done)));
    }

    private static SetupProgressSubMilestoneDto sub(String key, String label, int points, boolean completed) {
        return new SetupProgressSubMilestoneDto(key, label, points, completed);
    }

    private record StepDef(
            String key,
            String label,
            boolean required,
            boolean done,
            int earnedPoints,
            int maxPoints,
            String actionUrl,
            String recommendedSubKey,
            List<SetupProgressSubMilestoneDto> subMilestones
    ) {
    }
}
