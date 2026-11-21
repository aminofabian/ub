package zelisline.ub.globalcatalog.api;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.MedicineImportResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.MedicineTemplateResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.UpsertMedicineTemplateRequest;
import zelisline.ub.globalcatalog.application.GlobalCatalogMedicineService;

/**
 * Super-admin curation of medicine details on pharmacy catalogue products. Same authorization as
 * {@link SuperAdminGlobalCatalogController}; platform data, so there is no tenant to scope by.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/global-catalog")
@PreAuthorize("hasAuthority('PERM_sa.console.full')")
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogMedicineController {

    private final GlobalCatalogMedicineService medicineService;

    @GetMapping("/products/{id}/medicine")
    public MedicineTemplateResponse get(@PathVariable String id) {
        return medicineService.get(id);
    }

    @PutMapping("/products/{id}/medicine")
    public MedicineTemplateResponse upsert(
            @PathVariable String id,
            @Valid @RequestBody UpsertMedicineTemplateRequest body
    ) {
        return medicineService.upsert(id, body);
    }

    @DeleteMapping("/products/{id}/medicine")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id) {
        medicineService.remove(id);
    }

    @GetMapping(value = "/medicines/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String catalogId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalogue-medicines.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(medicineService.exportCsv(catalogId));
    }

    @PostMapping(value = "/medicines/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MedicineImportResponse importCsv(
            @RequestParam(required = false) String catalogId,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun,
            @RequestPart("file") MultipartFile file
    ) {
        try {
            return medicineService.importCsv(catalogId, file.getBytes(), dryRun);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file");
        }
    }
}
