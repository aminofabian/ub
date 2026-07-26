package zelisline.ub.marketplace.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalMessageRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalMessageRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalMessageShopOption;
import zelisline.ub.marketplace.application.SupplierPortalMessagesService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/messages")
@RequiredArgsConstructor
public class SupplierPortalMessagesController {

    private final SupplierPortalMessagesService messagesService;

    @GetMapping
    @PreAuthorize("hasRole('SUPPLIER')")
    public List<SupplierPortalMessageRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return messagesService.listForSupplier(principal.marketplaceSupplierId());
    }

    @GetMapping("/shops")
    @PreAuthorize("hasRole('SUPPLIER')")
    public List<SupplierPortalMessageShopOption> shops() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return messagesService.listShopsForSupplier(principal.marketplaceSupplierId());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPPLIER')")
    public SupplierPortalMessageRow send(@Valid @RequestBody CreateSupplierPortalMessageRequest body) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return messagesService.sendFromSupplier(
                principal.marketplaceSupplierId(),
                "Supplier",
                body);
    }

    @PostMapping("/{messageId}/read")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Object> markRead(@PathVariable String messageId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        messagesService.markRead(principal.marketplaceSupplierId(), messageId);
        return Map.of("ok", true);
    }
}
