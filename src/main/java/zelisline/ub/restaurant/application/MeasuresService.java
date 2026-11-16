package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MeasurePour;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MeasurePourResult;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MeasuresRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MeasuresResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OptionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OptionResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeIngredientRequest;

/**
 * Pour measures for a bar (ADR 0024). A spirit or wine sells by tot, single, double or glass; each
 * pour is a fraction of one stocked bottle. This helper spares the owner the arithmetic: give it the
 * bottle, the bottle's size and the pours, and it builds a "choose one" modifier group whose options
 * each carry a recipe taking {@code pourMl / bottleMl} of the bottle. Ringing a double then depletes
 * the bottle through the ordinary recipe path ({@link RecipeService}), fractional to four places.
 *
 * <p>It is orchestration over existing machinery — modifier groups ({@link MenuService}) and option
 * recipes ({@link RecipeService}) — so a measure drink behaves like any other dish with modifiers:
 * priced, sold in whole numbers, stock-deducted and reconciled the same way.
 *
 * <p>Re-running with the same group name <em>replaces</em> the pours on that group (existing options
 * kept by id, dropped pours removed), so an owner can adjust tot sizes without piling up groups.
 */
@Service
@RequiredArgsConstructor
public class MeasuresService {

    private static final String DEFAULT_GROUP_NAME = "Measure";
    private static final int QUANTITY_SCALE = 4;
    /** The smallest fraction {@link RecipeIngredientRequest} will accept; a smaller pour is refused. */
    private static final BigDecimal MIN_FRACTION = new BigDecimal("0.0001");

    private final MenuService menuService;
    private final RecipeService recipeService;
    private final ItemRepository itemRepository;
    private final RestaurantGate gate;

    /**
     * Sets up (or re-sets) the pour measures of one bottle and, when a drink is named, hangs them on it.
     *
     * @return the group that was built and the bottle fraction each pour takes
     */
    @Transactional
    public MeasuresResponse generate(String businessId, MeasuresRequest req) {
        gate.requireEnabled(businessId);

        Item bottle = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.bottleItemId().trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bottle not found"));
        if (!bottle.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    bottle.getName() + " is not stocked, so it cannot be poured from");
        }
        BigDecimal bottleMl = req.bottleMl();
        if (bottleMl.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The bottle size must be more than zero");
        }
        java.util.Set<String> seenLabels = new java.util.HashSet<>();
        for (MeasurePour pour : req.pours()) {
            if (!seenLabels.add(pour.label().trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Two pours are called \"" + pour.label().trim() + "\". Give each a different name.");
            }
            if (pour.pourMl().compareTo(bottleMl) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        pour.label() + " pours more than the whole bottle");
            }
            if (fraction(pour.pourMl(), bottleMl).compareTo(MIN_FRACTION) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        pour.label() + " is too small a pour for this bottle");
            }
        }

        if (req.dishMenuItemId() == null || req.dishMenuItemId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the drink these measures are for");
        }
        String dishId = req.dishMenuItemId().trim();

        // The owner prices each pour as the whole price a customer pays. The engine rings a dish as
        // its base price plus the chosen pour's add-on, so the dish base is the cheapest pour and each
        // pour's add-on is how much dearer it is than that — a single at 150 and a double at 280 then
        // ring at exactly 150 and 280, with nothing for the owner to reconcile.
        BigDecimal basePrice = req.pours().stream().map(MeasurePour::price)
                .min(BigDecimal::compareTo).orElseThrow();

        String groupName = req.groupName() == null || req.groupName().isBlank()
                ? DEFAULT_GROUP_NAME : req.groupName().trim();

        // Build, or replace in place, a "choose exactly one" group whose options are the pours. The
        // group to replace is the measures group ALREADY ON THIS DISH of that name, never some other
        // dish's group that happens to share the name — so re-running adjusts this drink's pours and
        // leaves every other menu item's modifiers untouched.
        Optional<String> existingGroupId = menuService.dishGroupNamed(businessId, dishId, groupName);
        List<OptionResponse> existingOptions = existingGroupId
                .map(id -> menuService.groupOptions(businessId, id))
                .orElse(List.of());

        List<OptionRequest> optionRequests = new ArrayList<>();
        int i = 0;
        for (MeasurePour pour : req.pours()) {
            // Keep an option's id when a pour of the same label already exists, so its history stays.
            String keepId = existingOptions.stream()
                    .filter(o -> o.name().equalsIgnoreCase(pour.label().trim()))
                    .map(OptionResponse::id).findFirst().orElse(null);
            BigDecimal delta = pour.price().subtract(basePrice).setScale(2, RoundingMode.HALF_UP);
            optionRequests.add(new OptionRequest(keepId, pour.label().trim(), delta, pour.preselected(), i++));
        }
        ModifierGroupRequest groupRequest =
                new ModifierGroupRequest(groupName, 1, 1, null, optionRequests);
        ModifierGroupResponse group = existingGroupId.isPresent()
                ? menuService.updateGroup(businessId, existingGroupId.get(), groupRequest)
                : menuService.createGroup(businessId, groupRequest);

        // Give each pour's option its recipe: its fraction of the one bottle.
        List<MeasurePourResult> results = new ArrayList<>();
        for (int p = 0; p < req.pours().size(); p++) {
            MeasurePour pour = req.pours().get(p);
            OptionResponse option = group.options().get(p);
            BigDecimal quantity = fraction(pour.pourMl(), bottleMl);
            recipeService.setOptionRecipe(businessId, option.id(),
                    List.of(new RecipeIngredientRequest(bottle.getId(), quantity, null, null)));
            results.add(new MeasurePourResult(option.id(), option.name(), quantity, pour.price()));
        }

        // The dish carries the pours and rings at each pour's own price (base + its add-on).
        menuService.setDishBasePrice(businessId, dishId, basePrice);
        menuService.attachGroup(businessId, dishId, group.id());

        return new MeasuresResponse(group.id(), bottle.getId(), bottleMl, results);
    }

    private static BigDecimal fraction(BigDecimal pourMl, BigDecimal bottleMl) {
        return pourMl.divide(bottleMl, QUANTITY_SCALE, RoundingMode.HALF_UP);
    }
}
