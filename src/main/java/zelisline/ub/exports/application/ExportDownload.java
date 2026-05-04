package zelisline.ub.exports.application;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

/** Resolved download artifact for streaming to the client. */
public record ExportDownload(Resource resource, MediaType mediaType, String filename) {
}
