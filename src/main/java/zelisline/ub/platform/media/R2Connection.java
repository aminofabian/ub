package zelisline.ub.platform.media;

/** A complete set of Cloudflare R2 bucket credentials, as saved by the super-admin. */
public record R2Connection(
        String endpoint,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        String publicBaseUrl
) {

    public static String endpointForAccount(String accountId) {
        return "https://" + accountId.trim() + ".r2.cloudflarestorage.com";
    }

    @Override
    public String toString() {
        return "R2Connection[endpoint=" + endpoint + ", bucket=" + bucket + ", publicBaseUrl=" + publicBaseUrl + "]";
    }
}
