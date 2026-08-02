package zelisline.ub.marketplace.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;

import zelisline.ub.marketplace.api.dto.GlobalSupplierHubResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalHubShopDetailResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalRestockBoardResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalSalesPulseResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalShopProductRow;
import zelisline.ub.marketplace.application.GlobalSupplierHubService;
import zelisline.ub.marketplace.application.SupplierPortalHubService;
import zelisline.ub.marketplace.application.SupplierPortalRestockBoardService;
import zelisline.ub.marketplace.application.SupplierPortalSalesPulseService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintRequest;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintResponse;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/hub")
@RequiredArgsConstructor
public class SupplierPortalHubController {

    private final SupplierPortalHubService supplierPortalHubService;
    private final GlobalSupplierHubService globalSupplierHubService;
    private final SupplierPortalSalesPulseService salesPulseService;
    private final SupplierPortalRestockBoardService restockBoardService;

    @GetMapping("/shops")
    @PreAuthorize("hasRole('SUPPLIER')")
    public GlobalSupplierHubResponse listShops() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return globalSupplierHubService.forMarketplaceSupplierId(principal.marketplaceSupplierId());
    }

    @GetMapping("/sales-pulse")
    @PreAuthorize("hasRole('SUPPLIER')")
    public SupplierPortalSalesPulseResponse salesPulse() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return salesPulseService.pulse(principal.marketplaceSupplierId());
    }

    @GetMapping("/restock-board")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Object restockBoard(
            @RequestParam(required = false, defaultValue = "week") String window,
            @RequestParam(required = false) String localSupplierId,
            @RequestParam(required = false, defaultValue = "json") String format
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        SupplierPortalRestockBoardResponse board = restockBoardService.board(
                principal.marketplaceSupplierId(), window, localSupplierId);
        String fmt = format == null ? "json" : format.trim().toLowerCase();
        if ("csv".equals(fmt)) {
            String filename = "restock-" + board.window() + "-" + board.windowEnd() + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(restockBoardService.toCsv(board));
        }
        if ("pdf".equals(fmt)) {
            String filename = "restock-" + board.window() + "-" + board.windowEnd() + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(restockBoardService.toPdf(board));
        }
        return board;
    }

    @GetMapping("/shops/{localSupplierId}/supplies")
    @PreAuthorize("hasRole('SUPPLIER')")
    public SupplierPortalHubShopDetailResponse shopSupplies(@PathVariable String localSupplierId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalHubService.shopSupplies(principal.marketplaceSupplierId(), localSupplierId);
    }

    @GetMapping("/shops/{localSupplierId}/products")
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public List<SupplierPortalShopProductRow> shopProducts(@PathVariable String localSupplierId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalHubService.shopProducts(principal.marketplaceSupplierId(), localSupplierId);
    }

    @PostMapping("/shops/{localSupplierId}/complaints")
    @PreAuthorize("hasPermission(null, 'supplier.catalog.write')")
    public PublicSupplierComplaintResponse submitComplaint(
            @PathVariable String localSupplierId,
            @Valid @RequestBody PublicSupplierComplaintRequest body,
            HttpServletRequest request
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return supplierPortalHubService.submitShopComplaint(
                principal.marketplaceSupplierId(), localSupplierId, body, request);
    }
}
