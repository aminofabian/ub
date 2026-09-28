package zelisline.ub.desktop.device;

import java.math.BigDecimal;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * Settings → Desktop → "Print test receipt": a short ESC/POS slip that
     * proves the printer path end to end without ringing up a sale.
     */
    @PostMapping("/print/test")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public void printTestSlip(
        @RequestParam(defaultValue = "58") int widthMm,
        HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        deviceService.printTestSlip(widthMm);
    }

    /**
     * Reachability of the local device sidecar (Tauri shell on :19500) — drives
     * the "device bridge" status pill in Settings. Never fails; a dead bridge is
     * reported as {@code reachable=false}.
     */
    @GetMapping("/health")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public DeviceBridge.BridgeHealth health(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return deviceService.deviceHealth();
    }

    /**
     * Settings → Desktop → "Restart backend". The shell takes the till stack down
     * and boots it again around the saved data; the request returns as soon as
     * the sidecar accepts it (the till then disappears for a few seconds and the
     * splash page takes over). Owner/admin/manager only — a settings action.
     */
    @PostMapping("/restart")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public void restartBackend(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        deviceService.restartBackend();
    }

    /** Settings → Desktop → "Open data folder", via the shell's file manager. */
    @PostMapping("/open-data-folder")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public void openDataFolder(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        deviceService.openDataFolder();
    }
}
