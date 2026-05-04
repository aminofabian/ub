package zelisline.ub.integrations.privacy.api.dto;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public record PrivacyExportDownload(Resource resource, MediaType mediaType, String filename) {}
