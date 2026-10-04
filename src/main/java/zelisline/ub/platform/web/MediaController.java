package zelisline.ub.platform.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import zelisline.ub.platform.media.ActiveR2ConnectionSource;
import zelisline.ub.platform.media.AttachmentStore;
import zelisline.ub.platform.media.CloudinarySignatureService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    static final String PROVIDER_CLOUDINARY = "cloudinary";
    static final String PROVIDER_R2 = "r2";
    static final String RESOURCE_RAW = "raw";
    private static final int MAX_FOLDER_LENGTH = 512;

    private final CloudinarySignatureService signatureService;
    private final MediaStore mediaStore;
    private final ActiveR2ConnectionSource r2Source;
    private final ObjectProvider<AttachmentStore> attachmentStore;

    public MediaController(
            CloudinarySignatureService signatureService,
            MediaStore mediaStore,
            ActiveR2ConnectionSource r2Source,
            ObjectProvider<AttachmentStore> attachmentStore) {
        this.signatureService = signatureService;
        this.mediaStore = mediaStore;
        this.r2Source = r2Source;
        this.attachmentStore = attachmentStore;
    }

    /**
     * Browser upload handshake. With uploads switched to R2 in super-admin the answer is
     * {@code provider=r2} and the client posts the file to {@link #upload} with the same
     * {@code resourceType}; otherwise it is a Cloudinary signature.
     */
    @PostMapping("/cloudinary-signature")
    public CloudinarySignatureResponse generateSignature(@Valid @RequestBody CloudinarySignatureRequest request) {
        boolean image = isImageResource(request.resourceType());
        boolean r2Ready = image ? r2Source.activeR2Connection().isPresent() : attachmentsOnR2();
        if (r2Ready) {
            return CloudinarySignatureResponse.viaBackend(request.folder().trim(), image);
        }
        if (!signatureService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloudinary is not configured on this server");
        }
        try {
            var result = signatureService.signUpload(request.folder(), request.resourceType());
            return new CloudinarySignatureResponse(
                    PROVIDER_CLOUDINARY,
                    result.cloudName(),
                    result.apiKey(),
                    result.timestamp(),
                    result.signature(),
                    result.folder(),
                    result.resourceType()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * Stores a file in the active store; the response mirrors Cloudinary's upload JSON.
     * {@code resourceType=auto|raw} stores a support attachment, anything else an image.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("folder") String folder,
            @RequestParam(value = "resourceType", required = false) String resourceType
    ) {
        if (folder == null || folder.isBlank() || folder.length() > MAX_FOLDER_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "folder is required");
        }
        if (!isImageResource(resourceType)) {
            return uploadAttachment(file, folder.trim());
        }
        if (!mediaStore.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is not configured");
        }
        CloudinaryUploadResult result = mediaStore.uploadImageToFolder(
                readBytes(file), file.getOriginalFilename(), folder.trim(), true);
        return toCloudinaryShape(result, CloudinarySignatureService.RESOURCE_IMAGE);
    }

    private Map<String, Object> uploadAttachment(MultipartFile file, String folder) {
        if (!attachmentsOnR2()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Attachment storage is not configured");
        }
        CloudinaryUploadResult result = attachmentStore.getObject()
                .uploadAttachment(readBytes(file), file.getOriginalFilename(), folder);
        return toCloudinaryShape(result, RESOURCE_RAW);
    }

    private boolean attachmentsOnR2() {
        AttachmentStore store = attachmentStore.getIfAvailable();
        return store != null && store.isActive();
    }

    private static boolean isImageResource(String resourceType) {
        return resourceType == null || resourceType.isBlank()
                || CloudinarySignatureService.RESOURCE_IMAGE.equals(resourceType.trim().toLowerCase(Locale.ROOT));
    }

    static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }

    public static Map<String, Object> toCloudinaryShape(CloudinaryUploadResult result, String resourceType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("public_id", result.publicId());
        body.put("secure_url", result.secureUrl());
        body.put("width", result.width());
        body.put("height", result.height());
        body.put("bytes", result.bytes());
        body.put("format", result.format());
        body.put("version", result.versionSignature());
        body.put("phash", result.phash());
        body.put("predominant_color", result.predominantColorHex());
        body.put("resource_type", resourceType);
        return body;
    }

    public record CloudinarySignatureRequest(
            @NotBlank @Size(max = MAX_FOLDER_LENGTH) String folder,
            /** {@code image} (default), {@code auto}, or {@code raw}. */
            @Size(max = 16) String resourceType
    ) {
    }

    public record CloudinarySignatureResponse(
            /** {@code cloudinary}: post to Cloudinary with the signature. {@code r2}: post to {@code /api/v1/media/upload}. */
            String provider,
            String cloudName,
            String apiKey,
            long timestamp,
            String signature,
            String folder,
            String resourceType
    ) {
        static CloudinarySignatureResponse viaBackend(String folder, boolean image) {
            String resourceType = image ? CloudinarySignatureService.RESOURCE_IMAGE : CloudinarySignatureService.RESOURCE_AUTO;
            return new CloudinarySignatureResponse(PROVIDER_R2, null, null, 0L, null, folder, resourceType);
        }
    }
}
