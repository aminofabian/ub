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
import zelisline.ub.restaurant.domain.ModifierGroup;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;

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
    private final ModifierGroupRepository groupRepository;
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
        for (MeasurePour pour : req.pours()) {
            if (pour.pourMl().compareTo(bottleMl) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        pour.label() + " pours more than the whole bottle");
            }
            if (fraction(pour.pourMl(), bottleMl).compareTo(MIN_FRACTION) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        pour.label() + " is too small a pour for this bottle");
            }
        }

        String groupName = req.groupName() == null || req.groupName().isBlank()
                ? DEFAULT_GROUP_NAME : req.groupName().trim();

        // Build, or replace in place, a "choose exactly one" group whose options are the pours.
        Optional<ModifierGroup> existing = groupRepository
                .findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId).stream()
                .filter(g -> groupName.equalsIgnoreCase(g.getName()))
                .findFirst();
        List<OptionResponse> existingOptions = existing
                .map(g -> menuService.groupOptions(businessId, g.getId()))
                .orElse(List.of());

        List<OptionRequest> optionRequests = new ArrayList<>();
        int i = 0;
        for (MeasurePour pour : req.pours()) {
            // Keep an option's id when a pour of the same label already exists, so its history stays.
            String keepId = existingOptions.stream()
                    .filter(o -> o.name().equalsIgnoreCase(pour.label().trim()))
                    .map(OptionResponse::id).findFirst().orElse(null);
            optionRequests.add(new OptionRequest(keepId, pour.label().trim(), pour.priceDelta(),
                    pour.preselected(), i++));
        }
        ModifierGroupRequest groupRequest =
                new ModifierGroupRequest(groupName, 1, 1, null, optionRequests);
        ModifierGroupResponse group = existing.isPresent()
                ? menuService.updateGroup(businessId, existing.get().getId(), groupRequest)
                : menuService.createGroup(businessId, groupRequest);

        // Give each pour's option its recipe: its fraction of the one bottle.
        List<MeasurePourResult> results = new ArrayList<>();
        for (int p = 0; p < req.pours().size(); p++) {
            MeasurePour pour = req.pours().get(p);
            OptionResponse option = group.options().get(p);
            BigDecimal quantity = fraction(pour.pourMl(), bottleMl);
            recipeService.setOptionRecipe(businessId, option.id(),
                    List.of(new RecipeIngredientRequest(bottle.getId(), quantity, null, null)));
            results.add(new MeasurePourResult(option.id(), option.name(), quantity, option.priceDelta()));
        }

        if (req.dishMenuItemId() != null && !req.dishMenuItemId().isBlank()) {
            menuService.attachGroup(businessId, req.dishMenuItemId().trim(), group.id());
        }

        return new MeasuresResponse(group.id(), bottle.getId(), bottleMl, results);
    }

    private static BigDecimal fraction(BigDecimal pourMl, BigDecimal bottleMl) {
        return pourMl.divide(bottleMl, QUANTITY_SCALE, RoundingMode.HALF_UP);
    }
}
