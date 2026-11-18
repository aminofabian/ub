package zelisline.ub.platform.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.BusinessTypeResponse;
import zelisline.ub.platform.api.dto.UpdateBusinessTypeRequest;
import zelisline.ub.platform.application.BusinessTypeCatalogService;

/**
 * Super-admin management of the business-type catalogue (ADR 0029). Secured by
 * {@code /api/v1/super-admin/**} (PERM_sa.console.full) in SecurityConfig. The code is a fixed enum
 * and is not editable here; Super Admin edits a type's label, hint, section, order, enabled flag and
 * image. Image upload mirrors {@link SuperAdminPlatformBrandingController}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/business-types")
@RequiredArgsConstructor
public class SuperAdminBusinessTypesController {

    private final BusinessTypeCatalogService service;

    @GetMapping
    public List<BusinessTypeResponse> list() {
        // The editor shows hidden types too, so it can re-enable them.
        return service.list(false);
    }

    @PutMapping("/{code}")
    public BusinessTypeResponse update(
            @PathVariable String code,
            @Valid @RequestBody UpdateBusinessTypeRequest request) {
        return service.update(code, request);
    }

    @PostMapping(value = "/{code}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BusinessTypeResponse> uploadImage(
            @PathVariable String code,
            @RequestPart("file") MultipartFile file) {
        byte[] bytes = readUploadBytes(file);
        BusinessTypeResponse body = service.uploadImage(code, bytes, file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/{code}/image")
    public BusinessTypeResponse clearImage(@PathVariable String code) {
        return service.clearImage(code);
    }

    private static byte[] readUploadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image to upload");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
        if (!looksLikeImage(bytes)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "That file is not a PNG, JPG, WebP, GIF or SVG image.");
        }
        return bytes;
    }

    /**
     * Server-side content sniff on the file's leading bytes (defence in depth; the Content-Type
     * header and the frontend check are not trusted). Accepts the same image kinds the upload UI
     * offers: PNG, JPEG, WebP, GIF, and SVG (an XML/text document).
     */
    private static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 4) {
            return false;
        }
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return true;
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return true;
        }
        // GIF: "GIF8"
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return true;
        }
        // WebP: "RIFF"...."WEBP"
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return true;
        }
        // SVG: an XML/text document — look for "<svg" or "<?xml" near the start, skipping a BOM/space.
        String head = new String(b, 0, Math.min(b.length, 512), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading()
                .toLowerCase(java.util.Locale.ROOT);
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"));
    }
}
