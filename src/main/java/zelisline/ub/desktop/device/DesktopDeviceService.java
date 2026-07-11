package zelisline.ub.desktop.device;

import java.math.BigDecimal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.receipt.SaleReceiptService;

@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopDeviceService {

    private final SaleReceiptService saleReceiptService;
    private final DeviceBridge deviceBridge;

    public void printSaleReceipt(String businessId, String saleId, int widthMm) {
        printSaleReceipt(businessId, saleId, widthMm, null);
    }

    public void printSaleReceipt(String businessId, String saleId, int widthMm, BigDecimal cashReceived) {
        byte[] escpos = saleReceiptService.buildEscPos(businessId, saleId, widthMm, cashReceived);
        deviceBridge.printEscPos(escpos);
    }

    public void kickCashDrawer() {
        deviceBridge.openCashDrawer();
    }
}
