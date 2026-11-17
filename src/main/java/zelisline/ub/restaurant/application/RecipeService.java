package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.inventory.application.StockOnHandReader;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ChosenModifier;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeIngredientRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeIngredientResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse;
import zelisline.ub.restaurant.domain.RecipeLine;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;
import zelisline.ub.restaurant.repository.RecipeLineRepository;

/**
 * What a dish is made of (ADR 0020, phase 1.5). A recipe lists the stocked ingredients of a dish
 * or of a modifier option. When the dish is sold its ingredients come off the stock count and
 * what they cost becomes the dish's cost of goods, so a plate has a real margin.
 *
 * <p>A wrong count never stops a sale: an ingredient the count says has run out is still used,
 * and its on-hand goes negative for the next stock count to correct.
 */
@Service
@RequiredArgsConstructor
public class RecipeService {

    public static final String REFERENCE_ORDER_LINE_WASTE = "order_line_waste";
    public static final String REFERENCE_PREP_BATCH = "prep_batch";

    private static final int MAX_INGREDIENTS = 40;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final java.time.ZoneId NAIROBI = java.time.ZoneId.of("Africa/Nairobi");

    private final RecipeLineRepository recipeRepository;
    private final MenuItemRepository menuItemRepository;
    private final ModifierOptionRepository optionRepository;
    private final ItemRepository itemRepository;
    private final InventoryBatchPickerService stockPicker;
    private final StockOnHandReader stockOnHandReader;
    private final zelisline.ub.inventory.application.InventoryLedgerService inventoryLedgerService;
    private final zelisline.ub.finance.application.LedgerPostingPort ledgerPostingPort;
    private final zelisline.ub.finance.application.LedgerAccountResolver ledgerAccountResolver;
    private final RestaurantGate gate;

    // ------------------------------------------------------------------ managing recipes

    @Transactional(readOnly = true)
    public RecipeResponse dishRecipe(String businessId, String menuItemId) {
        gate.requireEnabled(businessId);
        requireDish(businessId, menuItemId);
        return toResponse(businessId,
                recipeRepository.findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(businessId, menuItemId));
    }

    @Transactional(readOnly = true)
    public RecipeResponse optionRecipe(String businessId, String optionId) {
        gate.requireEnabled(businessId);
        requireOption(businessId, optionId);
        return toResponse(businessId,
                recipeRepository.findByBusinessIdAndModifierOptionIdOrderBySortOrderAsc(businessId, optionId));
    }

    /** Replaces the dish's recipe. An empty list means the dish has none and costs nothing to make. */
    @Transactional
    public RecipeResponse setDishRecipe(String businessId, String menuItemId, List<RecipeIngredientRequest> lines) {
        gate.requireEnabled(businessId);
        requireDish(businessId, menuItemId);
        recipeRepository.deleteByBusinessIdAndMenuItemId(businessId, menuItemId);
        recipeRepository.flush();
        return toResponse(businessId, save(businessId, menuItemId, null, null, lines));
    }

    @Transactional
    public RecipeResponse setOptionRecipe(String businessId, String optionId, List<RecipeIngredientRequest> lines) {
        gate.requireEnabled(businessId);
        requireOption(businessId, optionId);
        recipeRepository.deleteByBusinessIdAndModifierOptionId(businessId, optionId);
        recipeRepository.flush();
        return toResponse(businessId, save(businessId, null, optionId, null, lines));
    }

    private List<RecipeLine> save(
            String businessId, String menuItemId, String optionId, String producedItemId,
            List<RecipeIngredientRequest> lines) {
        List<RecipeLine> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        if (lines.size() > MAX_INGREDIENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A recipe has at most " + MAX_INGREDIENTS + " ingredients");
        }
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (RecipeIngredientRequest line : lines) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(line.itemId().trim(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingredient not found"));
            if (!item.isStocked()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        item.getName() + " is not stocked, so it cannot be an ingredient");
            }
            if (!seen.add(item.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.getName() + " is in the recipe twice");
            }
            RecipeLine row = new RecipeLine();
            row.setBusinessId(businessId);
            row.setMenuItemId(menuItemId);
            row.setModifierOptionId(optionId);
            row.setProducedItemId(producedItemId);
            row.setItemId(item.getId());
            row.setSubstituteItemId(substituteFor(businessId, item, line.substituteItemId()));
            row.setQuantity(line.quantity().setScale(4, RoundingMode.HALF_UP));
            row.setYieldPercent(line.yieldPercent() == null || line.yieldPercent().compareTo(HUNDRED) >= 0
                    ? null : line.yieldPercent().setScale(2, RoundingMode.HALF_UP));
            row.setSortOrder(order++);
            out.add(recipeRepository.save(row));
        }
        return out;
    }

    /** The stand-in named for an ingredient, checked: a different stocked product of this business. */
    private String substituteFor(String businessId, Item ingredient, String substituteItemId) {
        if (substituteItemId == null || substituteItemId.isBlank()) {
            return null;
        }
        Item substitute = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(substituteItemId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stand-in product not found"));
        if (substitute.getId().equals(ingredient.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ingredient.getName() + " cannot stand in for itself");
        }
        if (!substitute.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    substitute.getName() + " is not stocked, so it cannot stand in for " + ingredient.getName());
        }
        return substitute.getId();
    }

    // ------------------------------------------------------------------ at the sale

    /**
     * Takes the ingredients of dishes just sold off the stock count and says what they cost.
     *
     * @param chosen the modifier options on the line; those with a recipe of their own add to it
     * @param quantity how many of the dish were sold
     * @return the cost of all of them together; zero when the dish has no recipe
     */
    @Transactional
    public BigDecimal useIngredients(
            String businessId, String branchId, String saleId, String userId, String menuItemId,
            List<ChosenModifier> chosen, BigDecimal quantity) {
        Map<String, BigDecimal> needed = neededFromStock(businessId, menuItemId, null, chosen, quantity);
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : needed.entrySet()) {
            for (BatchAllocationLine taken : stockPicker.pickAndApplyIngredientUse(
                    businessId, entry.getKey(), branchId, entry.getValue(), saleId, userId)) {
                if (taken.unitCost() != null) {
                    cost = cost.add(taken.quantity().multiply(taken.unitCost()));
                }
            }
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Food that was cooked and then cancelled before anyone paid for it. Its ingredients are gone,
     * so they come off the count as wastage and their cost goes to shrinkage, not to cost of goods:
     * nothing was sold. Returns the cost; zero when the dish has no recipe.
     */
    @Transactional
    public BigDecimal wasteCookedLine(
            String businessId, String branchId, String orderLineId, String userId, String menuItemId,
            List<ChosenModifier> chosen, BigDecimal quantity) {
        Map<String, BigDecimal> needed = neededFromStock(businessId, menuItemId, null, chosen, quantity);
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : needed.entrySet()) {
            for (BatchAllocationLine taken : stockPicker.pickAndApplyIngredientUse(
                    businessId, entry.getKey(), branchId, entry.getValue(),
                    zelisline.ub.purchasing.PurchasingConstants.MOVEMENT_WASTAGE, REFERENCE_ORDER_LINE_WASTE, orderLineId,
                    userId, "Cooked, then cancelled")) {
                if (taken.unitCost() != null) {
                    cost = cost.add(taken.quantity().multiply(taken.unitCost()));
                }
            }
        }
        cost = cost.setScale(2, RoundingMode.HALF_UP);
        post(businessId, branchId, "restaurant_waste", orderLineId, "Cooked food cancelled before payment",
                zelisline.ub.finance.LedgerAccountCodes.INVENTORY_SHRINKAGE,
                zelisline.ub.finance.LedgerAccountCodes.INVENTORY, cost);
        return cost;
    }

    // ------------------------------------------------------------------ prepared batches

    @Transactional(readOnly = true)
    public RecipeResponse batchRecipe(String businessId, String itemId) {
        gate.requireEnabled(businessId);
        requirePrepared(businessId, itemId);
        return toResponse(businessId,
                recipeRepository.findByBusinessIdAndProducedItemIdOrderBySortOrderAsc(businessId, itemId));
    }

    /**
     * Sets what one unit of a prepared item is made of — one litre of soup, one kilogram of
     * marinated chicken. The item must be stocked, hold its own stock, and not be an ingredient of
     * itself.
     */
    @Transactional
    public RecipeResponse setBatchRecipe(String businessId, String itemId, List<RecipeIngredientRequest> lines) {
        gate.requireEnabled(businessId);
        requirePrepared(businessId, itemId);
        if (lines != null && lines.stream().anyMatch(l -> itemId.equals(l.itemId() == null ? null : l.itemId().trim()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A prepared item cannot be made from itself");
        }
        recipeRepository.deleteByBusinessIdAndProducedItemId(businessId, itemId);
        recipeRepository.flush();
        return toResponse(businessId, save(businessId, null, null, itemId, lines));
    }

    /**
     * Makes a batch of a prepared item: its ingredients leave the count and the batch joins it,
     * valued at what the ingredients cost. Nothing is earned or lost by making it — value moves
     * from the ingredients to the batch — so the two journals cancel on the equity account that a
     * stock increase posts against.
     *
     * @return the cost of the batch
     */
    @Transactional
    public BigDecimal makeBatch(
            String businessId, String branchId, String itemId, BigDecimal quantity, String userId) {
        gate.requireEnabled(businessId);
        Item prepared = requirePrepared(businessId, itemId);
        if (recipeRepository.findByBusinessIdAndProducedItemIdOrderBySortOrderAsc(businessId, itemId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say what " + prepared.getName() + " is made of before making a batch");
        }
        String batchRef = java.util.UUID.randomUUID().toString();
        Map<String, BigDecimal> needed = neededFromStock(businessId, null, itemId, null, quantity);
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : needed.entrySet()) {
            for (BatchAllocationLine taken : stockPicker.pickAndApplyIngredientUse(
                    businessId, entry.getKey(), branchId, entry.getValue(),
                    zelisline.ub.inventory.InventoryConstants.MOVEMENT_ADJUSTMENT, REFERENCE_PREP_BATCH, batchRef,
                    userId, "Used to make " + prepared.getName())) {
                if (taken.unitCost() != null) {
                    cost = cost.add(taken.quantity().multiply(taken.unitCost()));
                }
            }
        }
        cost = cost.setScale(2, RoundingMode.HALF_UP);
        BigDecimal unitCost = cost.divide(quantity, 4, RoundingMode.HALF_UP);
        // The mirror of the stock increase below, so equity is left where it was.
        post(businessId, branchId, "restaurant_prep_batch", batchRef, "Ingredients used to make " + prepared.getName(),
                zelisline.ub.finance.LedgerAccountCodes.OPENING_BALANCE_EQUITY,
                zelisline.ub.finance.LedgerAccountCodes.INVENTORY,
                quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP));
        inventoryLedgerService.recordStockIncrease(businessId,
                new zelisline.ub.inventory.api.dto.PostStockIncreaseRequest(
                        branchId, itemId, quantity, unitCost, "Prepared batch"),
                userId);
        return cost;
    }

    private Item requirePrepared(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        if (!item.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.getName() + " is not stocked");
        }
        return item;
    }

    /** One balanced two-line journal; nothing is posted for a zero amount. */
    private void post(
            String businessId, String branchId, String sourceType, String sourceId, String memo,
            String debitCode, String creditCode, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        zelisline.ub.finance.domain.JournalEntry entry = new zelisline.ub.finance.domain.JournalEntry();
        entry.setBusinessId(businessId);
        entry.setBranchId(branchId);
        entry.setEntryDate(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setMemo(memo);
        entry.debit(ledgerAccountResolver.resolveId(businessId, debitCode), amount);
        entry.credit(ledgerAccountResolver.resolveId(businessId, creditCode), amount);
        ledgerPostingPort.post(entry);
    }

    /** A voided sale gives its ingredients back to the count. */
    @Transactional
    public void restoreForVoidedSale(String businessId, String branchId, String saleId, String userId) {
        stockPicker.restoreIngredientUse(businessId, branchId, saleId, userId);
    }

    /** True when the business has written any recipe, so reports can say whether dish cost means anything. */
    @Transactional(readOnly = true)
    public boolean anyRecipe(String businessId) {
        return !recipeRepository.findByBusinessId(businessId).isEmpty();
    }

    /** Menu items of the business that have a recipe of their own. */
    @Transactional(readOnly = true)
    public Set<String> dishesWithRecipe(String businessId) {
        Set<String> out = new HashSet<>();
        for (RecipeLine line : recipeRepository.findByBusinessId(businessId)) {
            if (line.getMenuItemId() != null) {
                out.add(line.getMenuItemId());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ servings forecast

    /**
     * How many of a dish the branch can still make (ADR 0026).
     *
     * @param canMake            whole portions the usable stock allows, if only this dish were made
     * @param limitingIngredient the ingredient that runs out first
     * @param mustUseToday       portions of {@code canMake} that depend on stock expiring today
     */
    public record Makeable(long canMake, String limitingIngredient, long mustUseToday) {
    }

    /**
     * For every dish with a recipe, how many portions the branch's usable stock can produce. A dish
     * is counted on its own: two dishes sharing an ingredient each see all of it. Only stocked
     * ingredients limit a dish; one made only of things that are not counted (salt, water) is left
     * out of the result, as is a dish with no recipe. An ingredient's stand-in counts with it.
     * Choices on a dish are not included. Read-only, and never a reason to refuse a sale.
     */
    @Transactional(readOnly = true)
    public Map<String, Makeable> makeable(String businessId, String branchId) {
        Map<String, List<RecipeLine>> byDish = new HashMap<>();
        Set<String> itemIds = new HashSet<>();
        for (RecipeLine line : recipeRepository.findByBusinessId(businessId)) {
            if (line.getMenuItemId() == null) {
                continue;
            }
            byDish.computeIfAbsent(line.getMenuItemId(), k -> new ArrayList<>()).add(line);
            itemIds.add(line.getItemId());
            if (line.getSubstituteItemId() != null) {
                itemIds.add(line.getSubstituteItemId());
            }
        }
        Map<String, Makeable> out = new HashMap<>();
        if (byDish.isEmpty()) {
            return out;
        }
        Map<String, Item> stocked = new HashMap<>();
        for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)) {
            if (item.isStocked()) {
                stocked.put(item.getId(), item);
            }
        }
        Map<String, StockOnHandReader.Usable> stock = stockOnHandReader.usableByItemAtBranch(
                businessId, branchId, stocked.keySet(), java.time.LocalDate.now(NAIROBI));

        Map<String, BigDecimal> usable = new HashMap<>();
        Map<String, BigDecimal> usableAfterToday = new HashMap<>();
        stock.forEach((id, have) -> {
            usable.put(id, have.usable());
            usableAfterToday.put(id, have.usable().subtract(have.expiringToday()));
        });

        for (Map.Entry<String, List<RecipeLine>> dish : byDish.entrySet()) {
            // What one portion takes of each counted ingredient, in the recipe's own order, and what
            // may stand in for it.
            List<RecipeLine> lines = new ArrayList<>(dish.getValue());
            lines.sort(java.util.Comparator.comparingInt(RecipeLine::getSortOrder));
            Map<String, BigDecimal> perPortion = new java.util.LinkedHashMap<>();
            Map<String, List<String>> standIns = new HashMap<>();
            for (RecipeLine line : lines) {
                if (!stocked.containsKey(line.getItemId()) || fromStock(line).signum() <= 0) {
                    continue;
                }
                perPortion.merge(line.getItemId(), fromStock(line), BigDecimal::add);
                String standIn = line.getSubstituteItemId();
                if (standIn != null && stocked.containsKey(standIn)) {
                    List<String> known = standIns.computeIfAbsent(line.getItemId(), k -> new ArrayList<>());
                    if (!known.contains(standIn)) {
                        known.add(standIn);
                    }
                }
            }
            if (perPortion.isEmpty()) {
                continue;
            }
            long canMake = most(perPortion, standIns, usable);
            String shortFirst = shortAt(canMake + 1, perPortion, standIns, usable);
            long afterToday = most(perPortion, standIns, usableAfterToday);
            out.put(dish.getKey(), new Makeable(canMake,
                    stocked.get(shortFirst == null ? perPortion.keySet().iterator().next() : shortFirst).getName(),
                    Math.max(0, canMake - afterToday)));
        }
        return out;
    }

    /** The most whole portions the stock allows: the largest count no ingredient falls short at. */
    private static long most(
            Map<String, BigDecimal> perPortion, Map<String, List<String>> standIns, Map<String, BigDecimal> stock) {
        // No more than if every ingredient had all of its stand-ins' stock to itself.
        long high = Long.MAX_VALUE;
        for (Map.Entry<String, BigDecimal> need : perPortion.entrySet()) {
            BigDecimal all = stock.getOrDefault(need.getKey(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            for (String standIn : standIns.getOrDefault(need.getKey(), List.of())) {
                all = all.add(stock.getOrDefault(standIn, BigDecimal.ZERO).max(BigDecimal.ZERO));
            }
            high = Math.min(high, portions(all, need.getValue()));
        }
        long low = 0;
        while (low < high) {
            long middle = low + (high - low + 1) / 2;
            if (shortAt(middle, perPortion, standIns, stock) == null) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * The first ingredient, in recipe order, there is not enough of to make this many portions; null
     * when there is enough of all. Stock is shared honestly: an ingredient is used for its own line
     * first, what is left of it may stand in for another, and a stand-in covers whole portions only
     * (a sale never splits one portion between an ingredient and its stand-in).
     */
    private static String shortAt(
            long count, Map<String, BigDecimal> perPortion, Map<String, List<String>> standIns,
            Map<String, BigDecimal> stock) {
        Map<String, BigDecimal> left = new HashMap<>();
        Map<String, Long> stillNeeded = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> need : perPortion.entrySet()) {
            BigDecimal have = stock.getOrDefault(need.getKey(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            long own = Math.min(count, portions(have, need.getValue()));
            left.put(need.getKey(), have.subtract(need.getValue().multiply(BigDecimal.valueOf(own))));
            stillNeeded.put(need.getKey(), count - own);
        }
        for (Map.Entry<String, Long> need : stillNeeded.entrySet()) {
            long missing = need.getValue();
            BigDecimal each = perPortion.get(need.getKey());
            for (String standIn : standIns.getOrDefault(need.getKey(), List.of())) {
                if (missing == 0) {
                    break;
                }
                BigDecimal have = left.computeIfAbsent(standIn,
                        id -> stock.getOrDefault(id, BigDecimal.ZERO).max(BigDecimal.ZERO));
                long covered = Math.min(missing, portions(have, each));
                left.put(standIn, have.subtract(each.multiply(BigDecimal.valueOf(covered))));
                missing -= covered;
            }
            if (missing > 0) {
                return need.getKey();
            }
        }
        return null;
    }

    /** Whole portions a quantity allows; nothing when there is none or less than none. */
    private static long portions(BigDecimal available, BigDecimal perPortion) {
        if (available.signum() <= 0) {
            return 0;
        }
        return available.divide(perPortion, 0, RoundingMode.DOWN).longValue();
    }

    /** How much of each ingredient leaves stock for a quantity of a dish with its chosen options. */
    private Map<String, BigDecimal> neededFromStock(
            String businessId, String menuItemId, String producedItemId, List<ChosenModifier> chosen,
            BigDecimal quantity) {
        List<RecipeLine> lines = new ArrayList<>(menuItemId != null
                ? recipeRepository.findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(businessId, menuItemId)
                : recipeRepository.findByBusinessIdAndProducedItemIdOrderBySortOrderAsc(businessId, producedItemId));
        List<String> optionIds = chosen == null ? List.of() : chosen.stream()
                .map(ChosenModifier::optionId).filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (!optionIds.isEmpty()) {
            lines.addAll(recipeRepository.findByBusinessIdAndModifierOptionIdInOrderBySortOrderAsc(businessId, optionIds));
        }
        Map<String, BigDecimal> needed = new HashMap<>();
        for (RecipeLine line : lines) {
            BigDecimal amount = fromStock(line).multiply(quantity);
            needed.merge(itemToUse(businessId, line, amount, needed), amount, BigDecimal::add);
        }
        return needed;
    }

    /**
     * The ingredient to take for a recipe line: the one it names, or its stand-in when the named
     * one has run out and the stand-in can cover the amount. "Run out" counts what this same sale
     * has already claimed, so two lines sharing an ingredient do not both think it is there. When
     * neither can cover it, the named ingredient is taken and its count goes below zero, as it
     * would with no stand-in: the sale is never refused, and the shortage shows where the recipe says.
     */
    private String itemToUse(String businessId, RecipeLine line, BigDecimal amount, Map<String, BigDecimal> claimed) {
        if (line.getSubstituteItemId() == null) {
            return line.getItemId();
        }
        if (available(businessId, line.getItemId(), claimed).compareTo(amount) >= 0) {
            return line.getItemId();
        }
        return available(businessId, line.getSubstituteItemId(), claimed).compareTo(amount) >= 0
                ? line.getSubstituteItemId() : line.getItemId();
    }

    /** What is on the count for an item, less what this sale has already claimed of it. */
    private BigDecimal available(String businessId, String itemId, Map<String, BigDecimal> claimed) {
        BigDecimal onHand = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .map(Item::getCurrentStock).orElse(null);
        return (onHand == null ? BigDecimal.ZERO : onHand).subtract(claimed.getOrDefault(itemId, BigDecimal.ZERO));
    }

    /** What one portion takes from stock: the plated quantity scaled up for what is lost. */
    private static BigDecimal fromStock(RecipeLine line) {
        BigDecimal yield = line.getYieldPercent();
        if (yield == null || yield.signum() <= 0 || yield.compareTo(HUNDRED) >= 0) {
            return line.getQuantity();
        }
        return line.getQuantity().multiply(HUNDRED).divide(yield, 4, RoundingMode.HALF_UP);
    }

    private RecipeResponse toResponse(String businessId, List<RecipeLine> lines) {
        List<RecipeIngredientResponse> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean costKnown = true;
        for (RecipeLine line : lines) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(line.getItemId(), businessId).orElse(null);
            Item substitute = line.getSubstituteItemId() == null ? null
                    : itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(line.getSubstituteItemId(), businessId).orElse(null);
            BigDecimal taken = fromStock(line);
            BigDecimal unitCost = item == null ? null : item.getBuyingPrice();
            BigDecimal cost = unitCost == null ? null : taken.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            if (cost == null) {
                costKnown = false;
            } else {
                total = total.add(cost);
            }
            out.add(new RecipeIngredientResponse(line.getItemId(), item == null ? "Removed product" : item.getName(),
                    item == null ? null : item.getUnitType(), line.getQuantity(), line.getYieldPercent(), taken,
                    unitCost, cost, item == null ? null : item.getCurrentStock(),
                    substitute == null ? null : substitute.getId(), substitute == null ? null : substitute.getName(),
                    substitute == null ? null : substitute.getCurrentStock()));
        }
        return new RecipeResponse(out, total.setScale(2, RoundingMode.HALF_UP), costKnown);
    }

    private void requireDish(String businessId, String menuItemId) {
        menuItemRepository.findByIdAndBusinessId(menuItemId, businessId)
                .filter(i -> i.getDeletedAt() == null && MenuService.KIND_DISH.equals(i.getKind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dish not found"));
    }

    private void requireOption(String businessId, String optionId) {
        optionRepository.findByIdAndBusinessId(optionId, businessId)
                .filter(o -> o.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Modifier option not found"));
    }
}
