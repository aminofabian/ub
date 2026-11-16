package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemSellability;
import zelisline.ub.catalog.application.ItemTaxResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.discounts.api.dto.ResolvedPriceResponse;
import zelisline.ub.discounts.application.DiscountResolutionService;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.CategoryRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.CategoryResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuItemRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuItemResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OptionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OptionResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.StationRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.StationResponse;
import zelisline.ub.restaurant.domain.KitchenStation;
import zelisline.ub.restaurant.domain.MenuCategory;
import zelisline.ub.restaurant.domain.MenuItem;
import zelisline.ub.restaurant.domain.MenuItemModifierGroup;
import zelisline.ub.restaurant.domain.ModifierGroup;
import zelisline.ub.restaurant.domain.ModifierOption;
import zelisline.ub.restaurant.repository.KitchenStationRepository;
import zelisline.ub.restaurant.repository.MenuCategoryRepository;
import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;

/**
 * The restaurant menu: headings, dishes and products, modifier groups, and kitchen stations.
 * The menu belongs to the business, not a branch; "run out" is therefore business-wide in this
 * phase (a known limit for a restaurant with two kitchens).
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    public static final String KIND_DISH = "DISH";
    public static final String KIND_ITEM = "ITEM";

    /** A menu is read whole by the order screen, so its size is bounded. */
    static final int MAX_MENU_ITEMS = 1000;
    static final int MAX_CATEGORIES = 100;
    static final int MAX_MODIFIER_GROUPS = 200;
    static final int MAX_STATIONS = 20;

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final ModifierGroupRepository groupRepository;
    private final ModifierOptionRepository optionRepository;
    private final MenuItemModifierGroupRepository linkRepository;
    private final KitchenStationRepository stationRepository;
    private final ItemRepository itemRepository;
    private final ItemTaxResolver itemTaxResolver;
    private final DiscountResolutionService discountResolutionService;
    private final RestaurantGate gate;
    private final zelisline.ub.platform.media.PictureUploads pictureUploads;
    private final ApplicationEventPublisher eventPublisher;

    // ------------------------------------------------------------------ read

    /**
     * The whole menu. With a branch, products carry their shelf price there; without one (the
     * management screen) they carry none.
     */
    @Transactional(readOnly = true)
    public MenuResponse menu(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        List<MenuItem> items = menuItemRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId);
        Map<String, List<String>> groupIdsByItem = new HashMap<>();
        for (MenuItemModifierGroup link : linkRepository.findByBusinessIdOrderBySortOrderAsc(businessId)) {
            groupIdsByItem.computeIfAbsent(link.getMenuItemId(), k -> new ArrayList<>()).add(link.getGroupId());
        }
        List<String> productIds = items.stream()
                .filter(i -> KIND_ITEM.equals(i.getKind()) && i.getItemId() != null)
                .map(MenuItem::getItemId).distinct().toList();
        Map<String, Item> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(productIds, businessId)) {
                products.put(item.getId(), item);
            }
        }
        Map<String, ResolvedPriceResponse> prices = branchId == null || productIds.isEmpty()
                ? Map.of()
                : discountResolutionService.resolveForItems(businessId, branchId, productIds);

        List<MenuCategory> categories =
                categoryRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId);
        Map<String, MenuCategory> categoryById = new LinkedHashMap<>();
        for (MenuCategory category : categories) {
            categoryById.put(category.getId(), category);
        }

        List<MenuItemResponse> itemResponses = new ArrayList<>();
        MenuClock.At at = MenuClock.At.now();
        for (MenuItem item : items) {
            BigDecimal price = KIND_DISH.equals(item.getKind()) ? MenuClock.price(item, at) : item.getPrice();
            boolean sellable = true;
            String name = item.getName();
            if (KIND_ITEM.equals(item.getKind())) {
                Item product = products.get(item.getItemId());
                sellable = product != null && ItemSellability.sellabilityViolation(product) == null;
                ResolvedPriceResponse resolved = prices.get(item.getItemId());
                price = resolved == null ? null
                        : resolved.finalPrice() != null ? resolved.finalPrice() : resolved.regularPrice();
            }
            itemResponses.add(toResponse(item, name, price, sellable,
                    groupIdsByItem.getOrDefault(item.getId(), List.of()),
                    item.getCategoryId() == null ? null : categoryById.get(item.getCategoryId()), at));
        }

        Map<String, List<OptionResponse>> optionsByGroup = new LinkedHashMap<>();
        for (ModifierOption option : optionRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId)) {
            optionsByGroup.computeIfAbsent(option.getGroupId(), k -> new ArrayList<>()).add(toResponse(option));
        }
        List<ModifierGroupResponse> groups = groupRepository
                .findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId).stream()
                .map(g -> new ModifierGroupResponse(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                        g.getSortOrder(), optionsByGroup.getOrDefault(g.getId(), List.of())))
                .toList();

        return new MenuResponse(
                categories.stream().map(MenuService::toResponse).toList(),
                itemResponses,
                groups,
                stationRepository.findByBusinessIdOrderBySortOrderAscNameAsc(businessId).stream()
                        .map(MenuService::toResponse).toList());
    }

    // ------------------------------------------------------------------ stations

    @Transactional
    public StationResponse createStation(String businessId, StationRequest req) {
        gate.requireEnabled(businessId);
        if (stationRepository.findByBusinessIdOrderBySortOrderAscNameAsc(businessId).size() >= MAX_STATIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many kitchen stations");
        }
        KitchenStation station = new KitchenStation();
        station.setBusinessId(businessId);
        applyStation(station, req);
        return toResponse(stationRepository.save(station));
    }

    @Transactional
    public StationResponse updateStation(String businessId, String id, StationRequest req) {
        gate.requireEnabled(businessId);
        KitchenStation station = stationRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> notFound("Kitchen station"));
        applyStation(station, req);
        return toResponse(stationRepository.save(station));
    }

    private static void applyStation(KitchenStation station, StationRequest req) {
        station.setName(req.name().trim());
        if (req.sortOrder() != null) {
            station.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            station.setActive(req.active());
        }
        applyPrinter(station, req);
    }

    private static void applyPrinter(KitchenStation station, StationRequest req) {
        if (Boolean.TRUE.equals(req.clearPrinter())) {
            station.setPrinterName(null);
            station.setPrinterHost(null);
            station.setPrinterPort(null);
            return;
        }
        String name = blankToNull(req.printerName());
        String host = blankToNull(req.printerHost());
        Integer port = req.printerPort();
        if (name == null && host == null && port == null) {
            return; // nothing about the printer was sent: leave it as it is.
        }
        if (name != null && (host != null || port != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A station's printer is a name, or a host and port, not both");
        }
        if (name == null && (host == null || port == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A network printer needs a host and a port");
        }
        station.setPrinterName(name);
        station.setPrinterHost(host);
        station.setPrinterPort(name != null ? null : port);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ categories

    @Transactional
    public CategoryResponse createCategory(String businessId, CategoryRequest req) {
        gate.requireEnabled(businessId);
        if (categoryRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId).size()
                >= MAX_CATEGORIES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many menu headings");
        }
        MenuCategory category = new MenuCategory();
        category.setBusinessId(businessId);
        applyCategory(businessId, category, req);
        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse updateCategory(String businessId, String id, CategoryRequest req) {
        gate.requireEnabled(businessId);
        MenuCategory category = requireCategory(businessId, id);
        applyCategory(businessId, category, req);
        return toResponse(categoryRepository.save(category));
    }

    /** Removes the heading; its dishes stay on the menu without one. */
    @Transactional
    public void deleteCategory(String businessId, String id) {
        gate.requireEnabled(businessId);
        MenuCategory category = requireCategory(businessId, id);
        category.setDeletedAt(Instant.now());
        categoryRepository.save(category);
        for (MenuItem item : menuItemRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId)) {
            if (id.equals(item.getCategoryId())) {
                item.setCategoryId(null);
                menuItemRepository.save(item);
            }
        }
    }

    private void applyCategory(String businessId, MenuCategory category, CategoryRequest req) {
        category.setName(req.name().trim());
        category.setStationId(resolveStationId(businessId, req.stationId()));
        if (req.sortOrder() != null) {
            category.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            category.setActive(req.active());
        }
        if (Boolean.TRUE.equals(req.clearServingHours())) {
            category.setServeFromMinute(null);
            category.setServeToMinute(null);
        } else if (req.serveFromMinute() != null || req.serveToMinute() != null) {
            if (req.serveFromMinute() == null || req.serveToMinute() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Serving hours need a start and an end");
            }
            category.setServeFromMinute(req.serveFromMinute());
            category.setServeToMinute(req.serveToMinute());
        }
    }

    private MenuCategory requireCategory(String businessId, String id) {
        return categoryRepository.findByIdAndBusinessId(id, businessId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> notFound("Menu heading"));
    }

    // ------------------------------------------------------------------ menu items

    @Transactional
    public MenuItemResponse createMenuItem(String businessId, MenuItemRequest req) {
        gate.requireEnabled(businessId);
        if (menuItemRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId).size()
                >= MAX_MENU_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many menu items");
        }
        String kind = req.kind() == null ? "" : req.kind().trim().toUpperCase(java.util.Locale.ROOT);
        if (!KIND_DISH.equals(kind) && !KIND_ITEM.equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A menu item is a dish or a product");
        }
        MenuItem item = new MenuItem();
        item.setBusinessId(businessId);
        item.setKind(kind);
        applyMenuItem(businessId, item, req, true);
        menuItemRepository.save(item);
        replaceGroups(businessId, item, req.modifierGroupIds());
        return single(businessId, item);
    }

    @Transactional
    public MenuItemResponse updateMenuItem(String businessId, String id, MenuItemRequest req) {
        gate.requireEnabled(businessId);
        MenuItem item = requireMenuItem(businessId, id);
        if (req.kind() != null && !req.kind().trim().equalsIgnoreCase(item.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A menu item cannot change between dish and product; remove it and add another");
        }
        applyMenuItem(businessId, item, req, false);
        menuItemRepository.save(item);
        if (req.modifierGroupIds() != null) {
            replaceGroups(businessId, item, req.modifierGroupIds());
        }
        return single(businessId, item);
    }

    @Transactional
    public void deleteMenuItem(String businessId, String id) {
        gate.requireEnabled(businessId);
        MenuItem item = requireMenuItem(businessId, id);
        item.setDeletedAt(Instant.now());
        menuItemRepository.save(item);
    }

    /** The kitchen has run out, or has it again. Told to every order screen. */
    @Transactional
    public MenuItemResponse setAvailable(String businessId, String id, boolean available) {
        gate.requireEnabled(businessId);
        MenuItem item = requireMenuItem(businessId, id);
        item.setAvailable(available);
        menuItemRepository.save(item);
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.MenuUpdatedEvent(businessId));
        return single(businessId, item);
    }

    /** Gives a menu item its picture, replacing any it had. */
    @Transactional
    public MenuItemResponse setPicture(String businessId, String id, byte[] bytes, String originalFilename) {
        gate.requireEnabled(businessId);
        MenuItem item = requireMenuItem(businessId, id);
        var stored = pictureUploads.upload(bytes, originalFilename, "ub/" + businessId + "/menu/" + item.getId());
        String old = item.getImagePublicId();
        item.setImageUrl(stored.secureUrl());
        item.setImagePublicId(stored.publicId());
        menuItemRepository.save(item);
        if (old != null && !old.equals(stored.publicId())) {
            pictureUploads.discard(old);
        }
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.MenuUpdatedEvent(businessId));
        return single(businessId, item);
    }

    @Transactional
    public MenuItemResponse removePicture(String businessId, String id) {
        gate.requireEnabled(businessId);
        MenuItem item = requireMenuItem(businessId, id);
        String old = item.getImagePublicId();
        item.setImageUrl(null);
        item.setImagePublicId(null);
        menuItemRepository.save(item);
        pictureUploads.discard(old);
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.MenuUpdatedEvent(businessId));
        return single(businessId, item);
    }

    private void applyMenuItem(String businessId, MenuItem item, MenuItemRequest req, boolean creating) {
        if (req.description() != null) {
            String text = req.description().replaceAll("[\\p{Cc}\\p{Cf}]+", " ").trim();
            item.setDescription(text.isEmpty() ? null : text);
        }
        if (req.categoryId() != null) {
            item.setCategoryId(req.categoryId().isBlank() ? null : requireCategory(businessId, req.categoryId()).getId());
        }
        if (req.stationId() != null) {
            item.setStationId(resolveStationId(businessId, req.stationId()));
        }
        if (req.sortOrder() != null) {
            item.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            item.setActive(req.active());
        }
        applyTimes(item, req);
        if (KIND_ITEM.equals(item.getKind())) {
            if (creating || req.itemId() != null) {
                if (req.itemId() == null || req.itemId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the product this menu item sells");
                }
                Item product = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.itemId().trim(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found"));
                String violation = ItemSellability.sellabilityViolation(product);
                if (violation != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, violation);
                }
                item.setItemId(product.getId());
                if (req.name() == null || req.name().isBlank()) {
                    item.setName(trim191(product.getName()));
                }
            }
            if (req.name() != null && !req.name().isBlank()) {
                item.setName(req.name().trim());
            }
            // A product keeps its shelf price and its category's tax rate: one price, one stock count.
            item.setPrice(null);
            item.setTaxRateId(null);
            return;
        }
        if (req.itemId() != null && !req.itemId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A dish does not point at a product");
        }
        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A dish needs a name");
            }
            item.setName(req.name().trim());
        } else if (creating) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A dish needs a name");
        }
        if (req.price() != null) {
            item.setPrice(req.price().setScale(2, RoundingMode.HALF_UP));
        } else if (creating) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A dish needs a price");
        }
        if (req.taxRateId() != null) {
            if (req.taxRateId().isBlank()) {
                item.setTaxRateId(null);
            } else {
                if (itemTaxResolver.resolveRate(businessId, req.taxRateId()) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tax rate not found");
                }
                item.setTaxRateId(req.taxRateId().trim());
            }
        }
    }

    /** Serving hours and happy hour. Each needs both its ends; happy hour is for a dish and needs a price. */
    private static void applyTimes(MenuItem item, MenuItemRequest req) {
        if (Boolean.TRUE.equals(req.clearServingHours())) {
            item.setServeFromMinute(null);
            item.setServeToMinute(null);
        } else if (req.serveFromMinute() != null || req.serveToMinute() != null) {
            if (req.serveFromMinute() == null || req.serveToMinute() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Serving hours need a start and an end");
            }
            item.setServeFromMinute(req.serveFromMinute());
            item.setServeToMinute(req.serveToMinute());
        }
        if (Boolean.TRUE.equals(req.clearHappyHour())) {
            item.setHappyPrice(null);
            item.setHappyFromMinute(null);
            item.setHappyToMinute(null);
            item.setHappyDays(null);
        } else if (req.happyPrice() != null || req.happyFromMinute() != null || req.happyToMinute() != null) {
            if (!KIND_DISH.equals(item.getKind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Happy hour is for a dish; a product's price is its shelf price");
            }
            if (req.happyPrice() == null || req.happyFromMinute() == null || req.happyToMinute() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Happy hour needs a price, a start and an end");
            }
            item.setHappyPrice(req.happyPrice().setScale(2, RoundingMode.HALF_UP));
            item.setHappyFromMinute(req.happyFromMinute());
            item.setHappyToMinute(req.happyToMinute());
            item.setHappyDays(req.happyDays() == null || req.happyDays().isBlank() ? null : req.happyDays());
        }
    }

    private void replaceGroups(String businessId, MenuItem item, List<String> groupIds) {
        linkRepository.deleteByBusinessIdAndMenuItemId(businessId, item.getId());
        linkRepository.flush();
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        if (!KIND_DISH.equals(item.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a dish has modifiers");
        }
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (String raw : groupIds) {
            ModifierGroup group = requireGroup(businessId, raw);
            if (!seen.add(group.getId())) {
                continue;
            }
            MenuItemModifierGroup link = new MenuItemModifierGroup();
            link.setBusinessId(businessId);
            link.setMenuItemId(item.getId());
            link.setGroupId(group.getId());
            link.setSortOrder(order++);
            linkRepository.save(link);
        }
    }

    MenuItem requireMenuItem(String businessId, String id) {
        if (id == null || id.isBlank()) {
            throw notFound("Menu item");
        }
        return menuItemRepository.findByIdAndBusinessId(id.trim(), businessId)
                .filter(i -> i.getDeletedAt() == null)
                .orElseThrow(() -> notFound("Menu item"));
    }

    private MenuItemResponse single(String businessId, MenuItem item) {
        List<String> groupIds = linkRepository.findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(businessId, item.getId())
                .stream().map(MenuItemModifierGroup::getGroupId).toList();
        MenuClock.At at = MenuClock.At.now();
        MenuCategory category = item.getCategoryId() == null ? null
                : categoryRepository.findByIdAndBusinessId(item.getCategoryId(), businessId).orElse(null);
        return toResponse(item, item.getName(),
                KIND_DISH.equals(item.getKind()) ? MenuClock.price(item, at) : item.getPrice(), true, groupIds,
                category, at);
    }

    private static MenuItemResponse toResponse(
            MenuItem item, String name, BigDecimal price, boolean sellable, List<String> groupIds,
            MenuCategory category, MenuClock.At at) {
        // A dish is served now only when its named menu (heading) is open and its own hours allow.
        boolean servedNow = MenuClock.served(item, category, at);
        return new MenuItemResponse(item.getId(), item.getCategoryId(), name, item.getKind(), item.getItemId(), price,
                item.getTaxRateId(), item.getStationId(), item.isAvailable(), item.isActive(), sellable,
                item.getSortOrder(), groupIds, item.getPrice(), MenuClock.happy(item, at), servedNow,
                item.getServeFromMinute(), item.getServeToMinute(), item.getHappyPrice(), item.getHappyFromMinute(),
                item.getHappyToMinute(), item.getHappyDays(), item.getDescription(), item.getImageUrl());
    }

    // ------------------------------------------------------------------ modifier groups

    @Transactional
    public ModifierGroupResponse createGroup(String businessId, ModifierGroupRequest req) {
        gate.requireEnabled(businessId);
        if (groupRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId).size()
                >= MAX_MODIFIER_GROUPS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many modifier groups");
        }
        ModifierGroup group = new ModifierGroup();
        group.setBusinessId(businessId);
        return saveGroup(businessId, group, req);
    }

    /**
     * Replaces a group and its options. An option sent with its id is kept; one left out is
     * removed from the menu. Sales already made keep their own snapshot either way.
     */
    @Transactional
    public ModifierGroupResponse updateGroup(String businessId, String id, ModifierGroupRequest req) {
        gate.requireEnabled(businessId);
        return saveGroup(businessId, requireGroup(businessId, id), req);
    }

    @Transactional
    public void deleteGroup(String businessId, String id) {
        gate.requireEnabled(businessId);
        ModifierGroup group = requireGroup(businessId, id);
        Instant now = Instant.now();
        group.setDeletedAt(now);
        groupRepository.save(group);
        for (ModifierOption option : optionRepository
                .findByBusinessIdAndGroupIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId, id)) {
            option.setDeletedAt(now);
            optionRepository.save(option);
        }
        linkRepository.deleteByBusinessIdAndGroupId(businessId, id);
    }

    @Transactional
    public OptionResponse setOptionAvailable(String businessId, String optionId, boolean available) {
        gate.requireEnabled(businessId);
        ModifierOption option = optionRepository.findByIdAndBusinessId(optionId, businessId)
                .filter(o -> o.getDeletedAt() == null)
                .orElseThrow(() -> notFound("Modifier option"));
        option.setAvailable(available);
        optionRepository.save(option);
        eventPublisher.publishEvent(new zelisline.ub.platform.realtime.RealtimeBridge.MenuUpdatedEvent(businessId));
        return toResponse(option);
    }

    private ModifierGroupResponse saveGroup(String businessId, ModifierGroup group, ModifierGroupRequest req) {
        if (req.minSelect() > req.maxSelect()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The fewest choices cannot exceed the most");
        }
        if (req.minSelect() > req.options().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There are fewer options than must be chosen");
        }
        long defaults = req.options().stream().filter(o -> Boolean.TRUE.equals(o.preselected())).count();
        if (defaults > req.maxSelect()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "More default options than may be chosen");
        }
        group.setName(req.name().trim());
        group.setMinSelect(req.minSelect());
        group.setMaxSelect(req.maxSelect());
        if (req.sortOrder() != null) {
            group.setSortOrder(req.sortOrder());
        }
        groupRepository.save(group);

        Map<String, ModifierOption> existing = new HashMap<>();
        for (ModifierOption option : optionRepository
                .findByBusinessIdAndGroupIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId, group.getId())) {
            existing.put(option.getId(), option);
        }
        List<OptionResponse> out = new ArrayList<>();
        int order = 0;
        for (OptionRequest optionReq : req.options()) {
            ModifierOption option = optionReq.id() == null ? null : existing.remove(optionReq.id());
            if (option == null) {
                if (optionReq.id() != null && !optionReq.id().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Modifier option not found in this group");
                }
                option = new ModifierOption();
                option.setBusinessId(businessId);
                option.setGroupId(group.getId());
            }
            option.setName(optionReq.name().trim());
            option.setPriceDelta(optionReq.priceDelta() == null
                    ? BigDecimal.ZERO.setScale(2) : optionReq.priceDelta().setScale(2, RoundingMode.HALF_UP));
            option.setDefaultOption(Boolean.TRUE.equals(optionReq.preselected()));
            option.setSortOrder(optionReq.sortOrder() != null ? optionReq.sortOrder() : order);
            order++;
            out.add(toResponse(optionRepository.save(option)));
        }
        Instant now = Instant.now();
        for (ModifierOption removed : existing.values()) {
            removed.setDeletedAt(now);
            optionRepository.save(removed);
        }
        return new ModifierGroupResponse(group.getId(), group.getName(), group.getMinSelect(), group.getMaxSelect(),
                group.getSortOrder(), out);
    }

    private ModifierGroup requireGroup(String businessId, String id) {
        if (id == null || id.isBlank()) {
            throw notFound("Modifier group");
        }
        return groupRepository.findByIdAndBusinessId(id.trim(), businessId)
                .filter(g -> g.getDeletedAt() == null)
                .orElseThrow(() -> notFound("Modifier group"));
    }

    // ------------------------------------------------------------------ shared

    /** Blank clears the station; anything else must be a station of this business. */
    private String resolveStationId(String businessId, String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }
        return stationRepository.findByIdAndBusinessId(stationId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kitchen station not found"))
                .getId();
    }

    private static String trim191(String name) {
        return name.length() > 191 ? name.substring(0, 191) : name;
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
    }

    private static StationResponse toResponse(KitchenStation s) {
        return new StationResponse(s.getId(), s.getName(), s.getSortOrder(), s.isActive(),
                s.getPrinterName(), s.getPrinterHost(), s.getPrinterPort());
    }

    private static CategoryResponse toResponse(MenuCategory c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getStationId(), c.getSortOrder(), c.isActive(),
                c.getServeFromMinute(), c.getServeToMinute());
    }

    private static OptionResponse toResponse(ModifierOption o) {
        return new OptionResponse(o.getId(), o.getName(), o.getPriceDelta(), o.isDefaultOption(), o.isAvailable(),
                o.getSortOrder());
    }
}
