package zelisline.ub.globalcatalog.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogJobDtos.CreateJobResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogJobDtos.JobResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ApplyMarginRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ApplyMarginResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.BackfillImagesRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.BackfillImagesResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CatalogSummaryResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CategoryResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CreateProductRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CreateSupplierTemplateRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CsvImportResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.MetaResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PackDetailResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PackSummaryResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PatchPackRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PatchProductRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PatchSupplierTemplateRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ProductResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ProductSupplierLinkResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PublishProductsRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PublishProductsResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.SourceBusinessResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.SourceItemResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.SupplierTemplateResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.UpsertCategoryRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.UpsertProductSupplierLinkRequest;
import zelisline.ub.globalcatalog.application.GlobalCatalogJobService;
import zelisline.ub.globalcatalog.application.SuperAdminGlobalCatalogCsvService;
import zelisline.ub.globalcatalog.application.SuperAdminGlobalCatalogPromoteService;
import zelisline.ub.globalcatalog.application.SuperAdminGlobalCatalogService;

/**
 * Super-admin curation for the platform global product catalog.
 *
 * <p>Secured by {@code ROLE_SUPER_ADMIN} via {@code /api/v1/super-admin/**}
 * and bypasses tenant resolution in {@code DomainBusinessResolverFilter}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/global-catalog")
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogController {

    private final SuperAdminGlobalCatalogService superAdminGlobalCatalogService;
    private final SuperAdminGlobalCatalogPromoteService promoteService;
    private final SuperAdminGlobalCatalogCsvService csvService;
    private final GlobalCatalogJobService globalCatalogJobService;

    @GetMapping("/catalogs")
    public List<CatalogSummaryResponse> listCatalogs() {
        return superAdminGlobalCatalogService.listCatalogs();
    }

    @GetMapping("/meta")
    public MetaResponse meta(@RequestParam(required = false) String catalogId) {
        return superAdminGlobalCatalogService.getMeta(catalogId);
    }

    @GetMapping("/products")
    public Page<ProductResponse> listProducts(
            @RequestParam(required = false) String catalogId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false, defaultValue = "false") boolean missingImage,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        return superAdminGlobalCatalogService.listProducts(
                catalogId, q, status, categoryId, missingImage, page, size);
    }

    @GetMapping("/products/{id}")
    public ProductResponse getProduct(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId
    ) {
        return superAdminGlobalCatalogService.getProduct(id, catalogId);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody CreateProductRequest body
    ) {
        return superAdminGlobalCatalogService.createProduct(catalogId, body);
    }

    @PatchMapping("/products/{id}")
    public ProductResponse patchProduct(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody PatchProductRequest body
    ) {
        return superAdminGlobalCatalogService.patchProduct(id, catalogId, body);
    }

    @PostMapping("/products/publish")
    public PublishProductsResponse publishProducts(@Valid @RequestBody PublishProductsRequest body) {
        return superAdminGlobalCatalogService.publishProducts(body);
    }

    @PostMapping("/products/apply-margin")
    public ApplyMarginResponse applyMargin(
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody ApplyMarginRequest body
    ) {
        return superAdminGlobalCatalogService.applyMargin(catalogId, body);
    }

    @PostMapping(value = "/products/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductResponse uploadImage(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @RequestPart("file") MultipartFile file
    ) {
        return superAdminGlobalCatalogService.uploadProductImage(id, catalogId, file);
    }

    @DeleteMapping("/products/{id}/image")
    public ProductResponse clearImage(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId
    ) {
        return superAdminGlobalCatalogService.clearProductImage(id, catalogId);
    }

    @PostMapping("/products/{id}/backfill-images")
    public BackfillImagesResponse backfillProductImages(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @RequestBody(required = false) BackfillImagesRequest body
    ) {
        return superAdminGlobalCatalogService.backfillAdoptedImages(id, catalogId, body);
    }

    @PostMapping("/products/backfill-images")
    public BackfillImagesResponse backfillProductsImages(
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody BackfillImagesRequest body
    ) {
        return superAdminGlobalCatalogService.backfillAdoptedImages(null, catalogId, body);
    }

    @GetMapping(value = "/products/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportProductsCsv(
            @RequestParam(required = false) String catalogId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean missingImage
    ) {
        byte[] body = csvService.exportCsv(catalogId, status, missingImage);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"global-catalog-products.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    @PostMapping(value = "/products/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CsvImportResponse importProductsCsv(
            @RequestParam(required = false) String catalogId,
            @RequestPart("file") MultipartFile file
    ) {
        return csvService.importCsv(catalogId, file);
    }

    @GetMapping("/categories")
    public List<CategoryResponse> listCategories(@RequestParam(required = false) String catalogId) {
        return superAdminGlobalCatalogService.listCategories(catalogId);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody UpsertCategoryRequest body
    ) {
        return superAdminGlobalCatalogService.createCategory(catalogId, body);
    }

    @PatchMapping("/categories/{id}")
    public CategoryResponse patchCategory(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody UpsertCategoryRequest body
    ) {
        return superAdminGlobalCatalogService.patchCategory(id, catalogId, body);
    }

    @GetMapping("/packs")
    public List<PackSummaryResponse> listPacks(@RequestParam(required = false) String catalogId) {
        return superAdminGlobalCatalogService.listPacks(catalogId);
    }

    @GetMapping("/packs/{id}")
    public PackDetailResponse getPack(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId
    ) {
        return superAdminGlobalCatalogService.getPack(id, catalogId);
    }

    @PatchMapping("/packs/{id}")
    public PackDetailResponse patchPack(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody PatchPackRequest body
    ) {
        return superAdminGlobalCatalogService.patchPack(id, catalogId, body);
    }

    @GetMapping("/suppliers")
    public List<SupplierTemplateResponse> listSupplierTemplates(
            @RequestParam(required = false) String catalogId
    ) {
        return superAdminGlobalCatalogService.listSupplierTemplates(catalogId);
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierTemplateResponse createSupplierTemplate(
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody CreateSupplierTemplateRequest body
    ) {
        return superAdminGlobalCatalogService.createSupplierTemplate(catalogId, body);
    }

    @PatchMapping("/suppliers/{id}")
    public SupplierTemplateResponse patchSupplierTemplate(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody PatchSupplierTemplateRequest body
    ) {
        return superAdminGlobalCatalogService.patchSupplierTemplate(id, catalogId, body);
    }

    @GetMapping("/products/{id}/suppliers")
    public List<ProductSupplierLinkResponse> listProductSuppliers(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId
    ) {
        return superAdminGlobalCatalogService.listProductSupplierLinks(id, catalogId);
    }

    @PutMapping("/products/{id}/suppliers")
    public ProductSupplierLinkResponse upsertProductSupplier(
            @PathVariable String id,
            @RequestParam(required = false) String catalogId,
            @Valid @RequestBody UpsertProductSupplierLinkRequest body
    ) {
        return superAdminGlobalCatalogService.upsertProductSupplierLink(id, catalogId, body);
    }

    @DeleteMapping("/products/{productId}/suppliers/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProductSupplier(
            @PathVariable String productId,
            @PathVariable String templateId,
            @RequestParam(required = false) String catalogId
    ) {
        superAdminGlobalCatalogService.deleteProductSupplierLink(productId, templateId, catalogId);
    }

    @GetMapping("/source-businesses")
    public List<SourceBusinessResponse> listSourceBusinesses() {
        return promoteService.listSourceBusinesses();
    }

    @GetMapping("/source-items")
    public Page<SourceItemResponse> listSourceItems(
            @RequestParam String businessId,
            @RequestParam(required = false) String catalogId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        return promoteService.listSourceItems(businessId, catalogId, q, page, size);
    }

    @PostMapping("/promote/preview")
    public PromoteResponse previewPromote(@Valid @RequestBody PromoteRequest body) {
        GlobalCatalogJobService.requireSyncPromoteSize(body);
        return promoteService.preview(body);
    }

    @PostMapping("/promote")
    public PromoteResponse promote(@Valid @RequestBody PromoteRequest body) {
        GlobalCatalogJobService.requireSyncPromoteSize(body);
        return promoteService.promote(body);
    }

    @PostMapping("/promote/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreateJobResponse enqueuePromote(@Valid @RequestBody PromoteRequest body) {
        return globalCatalogJobService.enqueuePromote(currentSuperAdminId(), body);
    }

    @GetMapping("/promote/jobs/{jobId}")
    public JobResponse getPromoteJob(@PathVariable String jobId) {
        return globalCatalogJobService.getPromoteJob(jobId);
    }

    private static String currentSuperAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof String id) || id.isBlank()) {
            return null;
        }
        return id;
    }
}
