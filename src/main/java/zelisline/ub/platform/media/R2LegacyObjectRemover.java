package zelisline.ub.platform.media;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Deletes a stored object by {@code publicId}. R2-era ids are the full key; Cloudinary-era
 * ids have no extension while their mirrored R2 copy is stored as {@code <publicId>.<ext>},
 * so both the exact key and {@code <publicId>.*} are removed.
 */
class R2LegacyObjectRemover {

    private static final int MAX_EXTENSION_MATCHES = 10;

    private final S3Client client;
    private final String bucket;

    R2LegacyObjectRemover(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    void delete(String publicId) {
        deleteKey(publicId);
        ListObjectsV2Request listing = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(publicId + ".")
                .maxKeys(MAX_EXTENSION_MATCHES)
                .build();
        client.listObjectsV2(listing).contents().stream()
                .map(S3Object::key)
                .filter(key -> key.indexOf('/', publicId.length()) < 0)
                .forEach(this::deleteKey);
    }

    private void deleteKey(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
