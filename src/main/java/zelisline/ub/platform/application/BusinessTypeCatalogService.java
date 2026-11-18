package zelisline.ub.platform.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.BusinessTypeResponse;
import zelisline.ub.platform.api.dto.UpdateBusinessTypeRequest;
import zelisline.ub.platform.domain.BusinessTypeCatalogDefaults;
import zelisline.ub.platform.domain.BusinessTypeCatalogEntry;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.platform.repository.BusinessTypeCatalogRepository;
import zelisline.ub.tenancy.api.dto.StoreTypeCodes;

/**
 * The business-type catalogue (ADR 0029). The type codes are a fixed enum
 * ({@link StoreTypeCodes}); only presentation (label, hint, image, section, order, enabled) is
 * data. The read model overlays the stored row onto the built-in default for each code, so a code
 * with no row still renders its default and a stored row for an unknown code is ignored. Images are
 * uploaded to the platform media account, mirroring {@link PlatformBrandingSettingsService}.
 */
@Service
@RequiredArgsConstructor
public class BusinessTypeCatalogService {

    private static final String IMAGE_FOLDER = "ub/platform/business-types";

    private final BusinessTypeCatalogRepository repository;
    private final MediaStore mediaStore;

    /**
     * The merged catalogue, in display order. Pass {@code enabledOnly} true for the merchant-facing
     * read (onboarding, hub, storefront), false for the Super Admin editor (which shows hidden types).
     */
    @Transactional(readOnly = true)
    public List<BusinessTypeResponse> list(boolean enabledOnly) {
        Map<String, BusinessTypeCatalogEntry> rows = new LinkedHashMap<>();
        for (BusinessTypeCatalogEntry row : repository.findAllByOrderByDisplayOrderAscCodeAsc()) {
            if (row.getCode() != null && StoreTypeCodes.isValidCode(row.getCode())) {
                rows.put(row.getCode(), row);
            }
        }

        List<BusinessTypeResponse> out = new ArrayList<>();
        for (BusinessTypeCatalogDefaults.Entry def : BusinessTypeCatalogDefaults.ALL) {
            BusinessTypeCatalogEntry row = rows.get(def.code());
            BusinessTypeResponse merged = merge(def, row);
            if (!enabledOnly || merged.enabled()) {
                out.add(merged);
            }
        }
        out.sort(java.util.Comparator.comparingInt(BusinessTypeResponse::displayOrder)
                .thenComparing(BusinessTypeResponse::code));
        return out;
    }

    @Transactional
    public BusinessTypeResponse update(String code, UpdateBusinessTypeRequest request) {
        String normalized = requireValidCode(code);
        BusinessTypeCatalogEntry row = loadOrDefault(normalized);
        row.setLabel(request.label());
        row.setHint(blankToNull(request.hint()));
        row.setSection(request.section());
        row.setDisplayOrder(request.displayOrder());
        row.setEnabled(request.enabled());
        row.setUpdatedAt(Instant.now());
        return merge(defaultFor(normalized), repository.save(row));
    }

    @Transactional
    public BusinessTypeResponse uploadImage(String code, byte[] fileBytes, String originalFilename) {
        String normalized = requireValidCode(code);
        if (!mediaStore.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Media storage is not configured; cannot upload a business-type image.");
        }
        BusinessTypeCatalogEntry row = loadOrDefault(normalized);
        String previousPublicId = row.getImagePublicId();
        CloudinaryUploadResult uploaded =
                mediaStore.uploadImageToFolder(fileBytes, originalFilename, IMAGE_FOLDER, false);
        row.setImageUrl(uploaded.secureUrl());
        row.setImagePublicId(uploaded.publicId());
        row.setUpdatedAt(Instant.now());
        BusinessTypeResponse out = merge(defaultFor(normalized), repository.save(row));
        if (previousPublicId != null && !previousPublicId.equals(uploaded.publicId())) {
            mediaStore.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessTypeResponse clearImage(String code) {
        String normalized = requireValidCode(code);
        BusinessTypeCatalogEntry row = loadOrDefault(normalized);
        String previousPublicId = row.getImagePublicId();
        row.setImageUrl(null);
        row.setImagePublicId(null);
        row.setUpdatedAt(Instant.now());
        BusinessTypeResponse out = merge(defaultFor(normalized), repository.save(row));
        if (previousPublicId != null && mediaStore.isConfigured()) {
            mediaStore.destroyImage(previousPublicId);
        }
        return out;
    }

    /**
     * Idempotently insert a row for any code that has none, using its built-in default. Never
     * overwrites an existing row (so an edited label/image survives). Called once at startup so the
     * Super Admin editor always has an editable row per code even on a database older than this feature.
     */
    @Transactional
    public int backfillMissingDefaults() {
        int created = 0;
        for (BusinessTypeCatalogDefaults.Entry def : BusinessTypeCatalogDefaults.ALL) {
            if (repository.existsById(def.code())) {
                continue;
            }
            BusinessTypeCatalogEntry row = new BusinessTypeCatalogEntry();
            row.setCode(def.code());
            row.setLabel(def.label());
            row.setHint(def.hint());
            row.setSection(def.section());
            row.setDisplayOrder(def.displayOrder());
            row.setEnabled(true);
            row.setUpdatedAt(Instant.now());
            repository.save(row);
            created++;
        }
        return created;
    }

    // --- helpers ---

    private BusinessTypeResponse merge(BusinessTypeCatalogDefaults.Entry def, BusinessTypeCatalogEntry row) {
        if (row == null) {
            return new BusinessTypeResponse(
                    def.code(), def.label(), def.hint(), def.section(), def.displayOrder(), true, null);
        }
        return new BusinessTypeResponse(
                def.code(),
                firstNonBlank(row.getLabel(), def.label()),
                firstNonBlank(row.getHint(), def.hint()),
                firstNonBlank(row.getSection(), def.section()),
                row.getDisplayOrder(),
                row.isEnabled(),
                blankToNull(row.getImageUrl()));
    }

    private BusinessTypeCatalogEntry loadOrDefault(String code) {
        return repository.findById(code).orElseGet(() -> {
            BusinessTypeCatalogDefaults.Entry def = defaultFor(code);
            BusinessTypeCatalogEntry row = new BusinessTypeCatalogEntry();
            row.setCode(def.code());
            row.setLabel(def.label());
            row.setHint(def.hint());
            row.setSection(def.section());
            row.setDisplayOrder(def.displayOrder());
            row.setEnabled(true);
            row.setUpdatedAt(Instant.now());
            return row;
        });
    }

    private static BusinessTypeCatalogDefaults.Entry defaultFor(String code) {
        return BusinessTypeCatalogDefaults.ALL.stream()
                .filter(e -> e.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown business type: " + code));
    }

    private static String requireValidCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!StoreTypeCodes.isValidCode(normalized)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown business type: " + code);
        }
        return normalized;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
