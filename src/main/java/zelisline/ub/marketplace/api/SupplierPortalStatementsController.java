package zelisline.ub.marketplace.api;

import java.time.YearMonth;

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
import zelisline.ub.marketplace.api.dto.SupplierPortalStatementResponse;
import zelisline.ub.marketplace.application.SupplierPortalStatementsService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/statements")
@RequiredArgsConstructor
public class SupplierPortalStatementsController {

    private final SupplierPortalStatementsService statementsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.orders.read')")
    public Object get(
            @RequestParam String localSupplierId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false, defaultValue = "json") String format
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        YearMonth now = YearMonth.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        SupplierPortalStatementResponse statement = statementsService.statement(
                principal.marketplaceSupplierId(), localSupplierId, y, m);

        String fmt = format == null ? "json" : format.trim().toLowerCase();
        if ("csv".equals(fmt)) {
            String filename = "statement-" + y + "-" + String.format("%02d", m) + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(statementsService.toCsv(statement));
        }
        if ("pdf".equals(fmt)) {
            String filename = "statement-" + y + "-" + String.format("%02d", m) + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(statementsService.toPdf(statement));
        }
        return statement;
    }
}
