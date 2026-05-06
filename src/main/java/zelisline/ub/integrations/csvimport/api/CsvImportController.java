package zelisline.ub.integrations.csvimport.api;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.csvimport.api.dto.CreateImportJobResponse;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.integrations.csvimport.api.dto.ImportJobResponse;
import zelisline.ub.integrations.csvimport.application.CsvImportApplicationService;
import zelisline.ub.integrations.csvimport.application.ImportJobEnqueueService;
import zelisline.ub.integrations.csvimport.application.LegacyBuyingPriceJsonImportService;
import zelisline.ub.integrations.csvimport.application.LegacyProductJsonImportService;
import zelisline.ub.integrations.csvimport.application.LegacySellingPriceJsonImportService;
import zelisline.ub.integrations.csvimport.application.LegacySupplierJsonImportService;
import zelisline.ub.integrations.csvimport.domain.ImportJob;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/integrations/imports")
@RequiredArgsConstructor
public class CsvImportController {

    private static final String ITEM_TEMPLATE_HEADER =
            "sku,name,item_type_key,barcode,unit_type,is_stocked,is_sellable,selling_price,reorder_level";
    private static final String SUPPLIER_TEMPLATE_HEADER =
            "name,code,supplier_type,vat_pin,status,notes";
    private static final String OPENING_TEMPLATE_HEADER =
            "branch_name,sku,quantity,unit_cost,notes";

    private final CsvImportApplicationService csvImportApplicationService;
    private final LegacyProductJsonImportService legacyProductJsonImportService;
    private final LegacySupplierJsonImportService legacySupplierJsonImportService;
    private final LegacyBuyingPriceJsonImportService legacyBuyingPriceJsonImportService;
    private final LegacySellingPriceJsonImportService legacySellingPriceJsonImportService;
    private final ImportJobEnqueueService importJobEnqueueService;

    @GetMapping(value = "/templates/items", produces = "text/csv")
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<String> templateItems() {
        return csvAttachment("items-import-template.csv", ITEM_TEMPLATE_HEADER + "\n");
    }

    @GetMapping(value = "/templates/suppliers", produces = "text/csv")
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<String> templateSuppliers() {
        return csvAttachment("suppliers-import-template.csv", SUPPLIER_TEMPLATE_HEADER + "\n");
    }

    @GetMapping(value = "/templates/opening-stock", produces = "text/csv")
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<String> templateOpeningStock() {
        return csvAttachment("opening-stock-import-template.csv", OPENING_TEMPLATE_HEADER + "\n");
    }

    @PostMapping(value = "/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importItems(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(csvImportApplicationService.dryRunItems(businessId, bytes));
        }
        CsvImportResponse res = csvImportApplicationService.commitItems(
                businessId,
                bytes,
                CurrentTenantUser.requireHuman(http).userId()
        );
        return toCommitResponse(res);
    }

    @PostMapping(value = "/suppliers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importSuppliers(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(csvImportApplicationService.dryRunSuppliers(businessId, bytes));
        }
        return toCommitResponse(csvImportApplicationService.commitSuppliers(businessId, bytes));
    }

    @PostMapping(value = "/opening-stock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importOpeningStock(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(csvImportApplicationService.dryRunOpeningStock(businessId, bytes));
        }
        CsvImportResponse res = csvImportApplicationService.commitOpeningStock(
                businessId,
                bytes,
                CurrentTenantUser.requireHuman(http).userId()
        );
        return toCommitResponse(res);
    }

    /**
     * Legacy product JSON export: array of objects or {@code { "products": [...] }}.
     * When rows carry {@code current_stock} &gt; 0, pass {@code branchId} for opening-balance posting.
     */
    @PostMapping(value = "/legacy-products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importLegacyProducts(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(required = false) String branchId,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(legacyProductJsonImportService.dryRun(businessId, bytes, branchId));
        }
        CsvImportResponse res = legacyProductJsonImportService.commit(
                businessId,
                bytes,
                CurrentTenantUser.requireHuman(http).userId(),
                branchId
        );
        return toCommitResponse(res);
    }

    /**
     * Legacy supplier JSON: top-level array, or {@code { "suppliers": [...] }} / {@code "vendors"}.
     * Fields: {@code name} (required), {@code code}, {@code supplier_type}, {@code vat_pin}, {@code status},
     * {@code notes}, optional {@code tax_exempt}, {@code credit_terms_days}, {@code credit_limit},
     * {@code payment_method_preferred}, {@code payment_details}. Accepts snake_case or camelCase keys.
     */
    @PostMapping(value = "/legacy-suppliers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importLegacySuppliers(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(legacySupplierJsonImportService.dryRun(businessId, bytes));
        }
        return toCommitResponse(legacySupplierJsonImportService.commit(businessId, bytes));
    }

    /**
     * Legacy buying prices: array or {@code buying_prices} / {@code costs}. Each object:
     * {@code item_id} (UUID), {@code supplier_id} (nullable UUID → SYS-UNASSIGNED when null), {@code price} (float → unit cost),
     * {@code effective_from} (unix), optional {@code notes}; {@code id}, {@code set_by}, {@code created_at} from exports are not written.
     * See {@link LegacyBuyingPriceJsonImportService}.
     */
    @PostMapping(value = "/legacy-buying-prices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importLegacyBuyingPrices(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(legacyBuyingPriceJsonImportService.dryRun(businessId, bytes));
        }
        return toCommitResponse(legacyBuyingPriceJsonImportService.commit(
                businessId,
                bytes,
                CurrentTenantUser.requireHuman(http).userId()));
    }

    /** Legacy selling price JSON; see {@link LegacySellingPriceJsonImportService}. */
    @PostMapping(value = "/legacy-selling-prices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CsvImportResponse> importLegacySellingPrices(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        String businessId = resolveBusinessId(http);
        byte[] bytes = file.getBytes();
        if (dryRun) {
            return ResponseEntity.ok(legacySellingPriceJsonImportService.dryRun(businessId, bytes));
        }
        return toCommitResponse(legacySellingPriceJsonImportService.commit(
                businessId,
                bytes,
                CurrentTenantUser.requireHuman(http).userId()));
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ImportJobResponse getImportJob(@PathVariable String jobId, HttpServletRequest http) {
        return importJobEnqueueService.get(jobId, resolveBusinessId(http));
    }

    @PostMapping(value = "/jobs/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CreateImportJobResponse> enqueueImportItems(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importJobEnqueueService.enqueue(
                ImportJob.Kind.items,
                dryRun,
                resolveBusinessId(http),
                CurrentTenantUser.requireHuman(http).userId(),
                file.getOriginalFilename(),
                file.getBytes()
        ));
    }

    @PostMapping(value = "/jobs/suppliers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CreateImportJobResponse> enqueueImportSuppliers(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importJobEnqueueService.enqueue(
                ImportJob.Kind.suppliers,
                dryRun,
                resolveBusinessId(http),
                CurrentTenantUser.requireHuman(http).userId(),
                file.getOriginalFilename(),
                file.getBytes()
        ));
    }

    @PostMapping(value = "/jobs/opening-stock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'integrations.imports.manage')")
    public ResponseEntity<CreateImportJobResponse> enqueueImportOpeningStock(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http
    ) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importJobEnqueueService.enqueue(
                ImportJob.Kind.opening_stock,
                dryRun,
                resolveBusinessId(http),
                CurrentTenantUser.requireHuman(http).userId(),
                file.getOriginalFilename(),
                file.getBytes()
        ));
    }

    private static String resolveBusinessId(HttpServletRequest http) {
        var principal = CurrentTenantUser.requireHuman(http);
        return TenantRequestIds.requireMatchingTenant(http, principal.businessId());
    }

    private static ResponseEntity<String> csvAttachment(String filename, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    private static ResponseEntity<CsvImportResponse> toCommitResponse(CsvImportResponse res) {
        if (!res.errors().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(res);
        }
        return ResponseEntity.ok(res);
    }
}
