package zelisline.ub.desktop.device;

import java.math.BigDecimal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.receipt.ReceiptEscPosRenderer;
import zelisline.ub.sales.receipt.SaleReceiptService;
import zelisline.ub.storefront.application.WebOrderReceiptService;

@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopDeviceService {

    private final SaleReceiptService saleReceiptService;
    private final WebOrderReceiptService webOrderReceiptService;
    private final DeviceBridge deviceBridge;

    public void printSaleReceipt(String businessId, String saleId, int widthMm) {
        printSaleReceipt(businessId, saleId, widthMm, null);
    }

    public void printSaleReceipt(String businessId, String saleId, int widthMm, BigDecimal cashReceived) {
        byte[] escpos = saleReceiptService.buildEscPos(businessId, saleId, widthMm, cashReceived);
        deviceBridge.printEscPos(escpos);
    }

    public void printWebOrderReceipt(String businessId, String orderId, int widthMm) {
        byte[] escpos = webOrderReceiptService.buildEscPos(businessId, orderId, widthMm);
        deviceBridge.printEscPos(escpos);
    }

    public void kickCashDrawer() {
        deviceBridge.openCashDrawer();
    }

    /** Settings → Desktop → "Print test receipt" — a short slip, no drawer kick. */
    public void printTestSlip(int widthMm) {
        deviceBridge.printEscPos(ReceiptEscPosRenderer.renderTestSlip(widthMm));
    }

    /** Whether the local device sidecar is reachable, for the printer status pill. */
    public DeviceBridge.BridgeHealth deviceHealth() {
        return deviceBridge.health();
    }

    /** Settings → Desktop → "Restart backend" — the shell reboots the stack. */
    public void restartBackend() {
        deviceBridge.restartBackend();
    }

    /** Settings → Desktop → "Open data folder" — opens APP_DATA in the file manager. */
    public void openDataFolder() {
        deviceBridge.openDataFolder();
    }
}
