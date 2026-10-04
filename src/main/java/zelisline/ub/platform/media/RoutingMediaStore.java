package zelisline.ub.platform.media;

import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cloud {@link MediaStore}: sends each call to R2 when the super-admin has switched uploads to
 * R2, otherwise to {@link CloudinaryImageService}. The choice is read per call, so switching
 * (or switching back) takes effect without a redeploy. The R2 client is reused until the saved
 * connection changes.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.media.local.enabled", havingValue = "false", matchIfMissing = true)
public class RoutingMediaStore implements MediaStore, DisposableBean {

    private final ActiveR2ConnectionSource r2Source;
    private final ObjectProvider<CloudinaryImageService> cloudinary;
    private final Function<R2Connection, R2MediaStore> r2Factory;

    private R2Connection openConnection;
    private R2MediaStore openStore;

    @Autowired
    public RoutingMediaStore(ActiveR2ConnectionSource r2Source, ObjectProvider<CloudinaryImageService> cloudinary) {
        this(r2Source, cloudinary, R2MediaStore::open);
    }

    RoutingMediaStore(
            ActiveR2ConnectionSource r2Source,
            ObjectProvider<CloudinaryImageService> cloudinary,
            Function<R2Connection, R2MediaStore> r2Factory) {
        this.r2Source = r2Source;
        this.cloudinary = cloudinary;
        this.r2Factory = r2Factory;
    }

    @Override
    public boolean isConfigured() {
        if (r2Source.activeR2Connection().isPresent()) {
            return true;
        }
        CloudinaryImageService fallback = cloudinary.getIfAvailable();
        return fallback != null && fallback.isConfigured();
    }

    @Override
    public CloudinaryUploadResult uploadImage(
            byte[] fileBytes, String originalFilename, String businessId, String itemId) {
        return current().uploadImage(fileBytes, originalFilename, businessId, itemId);
    }

    @Override
    public CloudinaryUploadResult uploadImageToFolder(byte[] fileBytes, String originalFilename, String folderPath) {
        return current().uploadImageToFolder(fileBytes, originalFilename, folderPath);
    }

    @Override
    public CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes, String originalFilename, String folderPath, boolean requestImageFingerprinting) {
        return current().uploadImageToFolder(fileBytes, originalFilename, folderPath, requestImageFingerprinting);
    }

    @Override
    public CloudinaryUploadResult uploadFromRemoteUrl(String remoteUrl, String folderPath) {
        return current().uploadFromRemoteUrl(remoteUrl, folderPath);
    }

    @Override
    public void destroyImage(String publicId) {
        Optional<R2Connection> r2 = r2Source.activeR2Connection();
        if (r2.isPresent()) {
            r2Store(r2.get()).destroyImage(publicId);
            return;
        }
        CloudinaryImageService fallback = cloudinary.getIfAvailable();
        if (fallback != null) {
            fallback.destroyImage(publicId);
        }
    }

    @Override
    public synchronized void destroy() {
        if (openStore != null) {
            openStore.close();
        }
    }

    private MediaStore current() {
        Optional<R2Connection> r2 = r2Source.activeR2Connection();
        if (r2.isPresent()) {
            return r2Store(r2.get());
        }
        CloudinaryImageService fallback = cloudinary.getIfAvailable();
        if (fallback == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is not configured");
        }
        return fallback;
    }

    private synchronized R2MediaStore r2Store(R2Connection connection) {
        if (openStore != null && connection.equals(openConnection)) {
            return openStore;
        }
        // The previous client is not closed: an upload may still be using it.
        openStore = r2Factory.apply(connection);
        openConnection = connection;
        return openStore;
    }
}
