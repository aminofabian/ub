package zelisline.ub.platform.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import zelisline.ub.platform.media.CloudinarySignatureService;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final CloudinarySignatureService signatureService;

    public MediaController(CloudinarySignatureService signatureService) {
        this.signatureService = signatureService;
    }

    @PostMapping("/cloudinary-signature")
    public CloudinarySignatureResponse generateSignature(@Valid @RequestBody CloudinarySignatureRequest request) {
        if (!signatureService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloudinary is not configured on this server");
        }
        var result = signatureService.signUpload(request.folder());
        return new CloudinarySignatureResponse(
                result.cloudName(),
                result.apiKey(),
                result.timestamp(),
                result.signature(),
                result.folder()
        );
    }

    public record CloudinarySignatureRequest(
            @NotBlank @Size(max = 512) String folder
    ) {
    }

    public record CloudinarySignatureResponse(
            String cloudName,
            String apiKey,
            long timestamp,
            String signature,
            String folder
    ) {
    }
}
