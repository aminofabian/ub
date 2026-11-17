package zelisline.ub.restaurant.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.AvailabilityRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.CategoryRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.CategoryResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuItemRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuItemResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierGroupResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OptionResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.StationRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.StationResponse;
import zelisline.ub.restaurant.application.MenuService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/** The restaurant menu: headings, dishes and products, modifier groups, kitchen stations (ADR 0020). */
@Validated
@RestController
@RequestMapping("/api/v1/restaurant")
@RequiredArgsConstructor
public class RestaurantMenuController {

    private static final String READ = "hasPermission(null, 'sales.sell') or hasPermission(null, 'restaurant.menu.manage')"
            + " or hasPermission(null, 'restaurant.kitchen.update')";
    private static final String MANAGE = "hasPermission(null, 'restaurant.menu.manage')";
    private static final String KITCHEN = "hasPermission(null, 'restaurant.kitchen.update')"
            + " or hasPermission(null, 'restaurant.menu.manage')";

    private final MenuService menuService;
    private final zelisline.ub.restaurant.application.RestaurantSettingsService settingsService;
    private final zelisline.ub.restaurant.application.RecipeService recipeService;
    private final zelisline.ub.restaurant.application.MeasuresService measuresService;
    private final BranchResolutionService branchResolutionService;

    /** The whole menu. With {@code branchId}, products carry their shelf price at that branch. */
    @GetMapping("/menu")
    @PreAuthorize(READ)
    public MenuResponse menu(@RequestParam(required = false) String branchId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchId == null || branchId.isBlank() ? null
                : branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId);
        return menuService.menu(TenantRequestIds.resolveBusinessId(request), branch, true);
    }

    /** How the restaurant runs: service charge, pooled tips, packaging and delivery charges, day start, QR ordering. */
    @GetMapping("/settings")
    @PreAuthorize(READ + " or hasPermission(null, 'restaurant.orders.write')")
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.SettingsResponse settings(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.read(TenantRequestIds.resolveBusinessId(request));
    }

    /** Null leaves a field as it is; zero switches a charge off. */
    @PutMapping("/settings")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.SettingsResponse updateSettings(
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.RestaurantDtos.SettingsRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return settingsService.update(TenantRequestIds.resolveBusinessId(request), caller.userId(), body);
    }

    /** What one unit of a prepared item (a litre of soup) is made of. */
    @GetMapping("/prep/{itemId}/recipe")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse batchRecipe(
            @PathVariable String itemId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return recipeService.batchRecipe(TenantRequestIds.resolveBusinessId(request), itemId);
    }

    @PutMapping("/prep/{itemId}/recipe")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse setBatchRecipe(
            @PathVariable String itemId,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return recipeService.setBatchRecipe(TenantRequestIds.resolveBusinessId(request), itemId, body.ingredients());
    }

    /**
     * Makes a batch of a prepared item: its ingredients leave the stock count and the batch joins
     * it, valued at what they cost. Not idempotent by itself — send an Idempotency-Key.
     */
    @PostMapping("/prep/{itemId}/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(KITCHEN)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.PrepBatchResponse makeBatch(
            @PathVariable String itemId,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.RestaurantDtos.PrepBatchRequest body,
            HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), body.branchId());
        java.math.BigDecimal cost = recipeService.makeBatch(TenantRequestIds.resolveBusinessId(request), branch, itemId,
                body.quantity(), caller.userId());
        return new zelisline.ub.restaurant.api.dto.RestaurantDtos.PrepBatchResponse(itemId, body.quantity(), cost);
    }

    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public StationResponse createStation(@Valid @RequestBody StationRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.createStation(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PutMapping("/stations/{id}")
    @PreAuthorize(MANAGE)
    public StationResponse updateStation(
            @PathVariable String id, @Valid @RequestBody StationRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.updateStation(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping("/menu/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.createCategory(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PutMapping("/menu/categories/{id}")
    @PreAuthorize(MANAGE)
    public CategoryResponse updateCategory(
            @PathVariable String id, @Valid @RequestBody CategoryRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.updateCategory(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/menu/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    public void deleteCategory(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        menuService.deleteCategory(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/menu/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public MenuItemResponse createItem(@Valid @RequestBody MenuItemRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.createMenuItem(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/menu/items/{id}")
    @PreAuthorize(MANAGE)
    public MenuItemResponse updateItem(
            @PathVariable String id, @Valid @RequestBody MenuItemRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.updateMenuItem(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/menu/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    public void deleteItem(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        menuService.deleteMenuItem(TenantRequestIds.resolveBusinessId(request), id);
    }

    /** Gives a menu item its picture (JPEG, PNG or WebP), replacing any it had. */
    @PostMapping(value = "/menu/items/{id}/picture", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGE)
    public MenuItemResponse setItemPicture(
            @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the picture");
        }
        return menuService.setPicture(TenantRequestIds.resolveBusinessId(request), id, bytes, file.getOriginalFilename());
    }

    @DeleteMapping("/menu/items/{id}/picture")
    @PreAuthorize(MANAGE)
    public MenuItemResponse removeItemPicture(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.removePicture(TenantRequestIds.resolveBusinessId(request), id);
    }

    /** The kitchen has run out of a dish, or has it again. */
    @PutMapping("/menu/items/{id}/availability")
    @PreAuthorize(KITCHEN)
    public MenuItemResponse setItemAvailable(
            @PathVariable String id, @Valid @RequestBody AvailabilityRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.setAvailable(TenantRequestIds.resolveBusinessId(request), id, body.available());
    }

    @PostMapping("/menu/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public ModifierGroupResponse createGroup(@Valid @RequestBody ModifierGroupRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.createGroup(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PutMapping("/menu/modifier-groups/{id}")
    @PreAuthorize(MANAGE)
    public ModifierGroupResponse updateGroup(
            @PathVariable String id, @Valid @RequestBody ModifierGroupRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.updateGroup(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/menu/modifier-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    public void deleteGroup(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        menuService.deleteGroup(TenantRequestIds.resolveBusinessId(request), id);
    }

    /**
     * Sets up a bottle's pour measures: builds a "choose one" modifier group whose options are the
     * pours (tot, single, double, glass), each taking its fraction of the bottle off the stock count
     * when sold. Re-running with the same group name replaces the pours. When a dish is named, the
     * group is hung on it too. The owner never computes 30/750.
     */
    @PostMapping("/menu/measures")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.MeasuresResponse measures(
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.RestaurantDtos.MeasuresRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return measuresService.generate(TenantRequestIds.resolveBusinessId(request), body);
    }

    /** What a dish is made of, with what it costs at today's buying prices. */
    @GetMapping("/menu/items/{id}/recipe")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse dishRecipe(
            @PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return recipeService.dishRecipe(TenantRequestIds.resolveBusinessId(request), id);
    }

    /** Replaces a dish's recipe; an empty list removes it. */
    @PutMapping("/menu/items/{id}/recipe")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse setDishRecipe(
            @PathVariable String id,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return recipeService.setDishRecipe(TenantRequestIds.resolveBusinessId(request), id, body.ingredients());
    }

    /** What a modifier option adds to a dish's ingredients ("double patty" uses one more patty). */
    @GetMapping("/menu/modifier-options/{id}/recipe")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse optionRecipe(
            @PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return recipeService.optionRecipe(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PutMapping("/menu/modifier-options/{id}/recipe")
    @PreAuthorize(MANAGE)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeResponse setOptionRecipe(
            @PathVariable String id,
            @Valid @RequestBody zelisline.ub.restaurant.api.dto.RestaurantDtos.RecipeRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return recipeService.setOptionRecipe(TenantRequestIds.resolveBusinessId(request), id, body.ingredients());
    }

    @PutMapping("/menu/modifier-options/{id}/availability")
    @PreAuthorize(KITCHEN)
    public OptionResponse setOptionAvailable(
            @PathVariable String id, @Valid @RequestBody AvailabilityRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return menuService.setOptionAvailable(TenantRequestIds.resolveBusinessId(request), id, body.available());
    }
}
