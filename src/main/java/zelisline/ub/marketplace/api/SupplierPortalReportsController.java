package zelisline.ub.marketplace.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.application.SupplierPortalReportsService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/reports")
@RequiredArgsConstructor
public class SupplierPortalReportsController {

    private final SupplierPortalReportsService reportsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public ResponseEntity<byte[]> export(@RequestParam String type) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        byte[] body = reportsService.exportCsv(principal.marketplaceSupplierId(), type);
        String filename = "supplier-" + type.trim().toLowerCase() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }
}
