package zelisline.ub.desktop.device;

import java.math.BigDecimal;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/devices")
@RequiredArgsConstructor
public class DesktopDeviceController {

    private final DesktopDeviceService deviceService;

    @PostMapping("/print/sale/{saleId}")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public void printSaleReceipt(
        @PathVariable String saleId,
        @RequestParam(defaultValue = "58") int widthMm,
        @RequestParam(required = false) BigDecimal cashReceived,
        HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        deviceService.printSaleReceipt(
            TenantRequestIds.resolveBusinessId(request),
            saleId,
            widthMm,
            cashReceived
        );
    }

    @PostMapping("/print/web-order/{orderId}")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'storefront.orders.read')")
    public void printWebOrderReceipt(
        @PathVariable String orderId,
        @RequestParam(defaultValue = "80") int widthMm,
        HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        deviceService.printWebOrderReceipt(
            TenantRequestIds.resolveBusinessId(request),
            orderId,
            widthMm
        );
    }

    @PostMapping("/drawer/kick")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public void kickDrawer(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        deviceService.kickCashDrawer();
    }
}
