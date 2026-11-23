package zelisline.ub.marketplace.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalMessageRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalMessageRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalMessageShopOption;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse;
import zelisline.ub.marketplace.application.SupplierPortalMessagesService;
import zelisline.ub.marketplace.application.SupplierPortalRestockBoardService;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * The first portal pages brought into a supplier's own shop: what to restock in the shops it supplies, and the
 * messages between it and those shops. Signed in to the shop as usual; the supplier is found through the shop,
 * and the portal's own services answer, so the shop and the portal show the same thing by the same rules.
 */
@RestController
@RequestMapping("/api/v1/supplier-page/supply")
@RequiredArgsConstructor
public class ShopSupplyBridgeController {

    private static final String TRADE_DESK = "hasPermission(null, 'storefront.orders.read')";

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierPortalRestockBoardService restockBoardService;
    private final SupplierPortalMessagesService messagesService;
    private final UserRepository userRepository;

    @GetMapping("/restock-board")
    @PreAuthorize(TRADE_DESK)
    public SupplierPortalRestockBoardResponse restockBoard(
            @RequestParam(required = false, defaultValue = "week") String window, HttpServletRequest request) {
        return restockBoardService.board(supplierId(request), window, null);
    }

    @GetMapping("/messages")
    @PreAuthorize(TRADE_DESK)
    public List<SupplierPortalMessageRow> messages(HttpServletRequest request) {
        return messagesService.listForSupplier(supplierId(request));
    }

    @GetMapping("/messages/shops")
    @PreAuthorize(TRADE_DESK)
    public List<SupplierPortalMessageShopOption> messageShops(HttpServletRequest request) {
        return messagesService.listShopsForSupplier(supplierId(request));
    }

    @PostMapping("/messages")
    @PreAuthorize(TRADE_DESK)
    public SupplierPortalMessageRow send(
            @Valid @RequestBody CreateSupplierPortalMessageRequest body, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        // The shop that receives it sees who wrote it, by name.
        String author = userRepository.findById(principal.userId())
                .map(user -> user.getName())
                .filter(name -> name != null && !name.isBlank())
                .orElse("Supplier");
        return messagesService.sendFromSupplier(supplierId(request), author, body);
    }

    @PostMapping("/messages/{messageId}/read")
    @PreAuthorize(TRADE_DESK)
    public Map<String, Object> markRead(@PathVariable String messageId, HttpServletRequest request) {
        messagesService.markRead(supplierId(request), messageId);
        return Map.of("ok", true);
    }

    private String supplierId(HttpServletRequest request) {
        return marketplaceSupplierRepository.findFirstByBusinessId(TenantRequestIds.resolveBusinessId(request))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This shop has no marketplace page"))
                .getId();
    }
}
