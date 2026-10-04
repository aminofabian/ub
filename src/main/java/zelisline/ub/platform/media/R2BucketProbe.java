package zelisline.ub.platform.media;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Confirms saved R2 credentials can write to the bucket before uploads are switched to it. */
@Component
public class R2BucketProbe {

    public void verify(R2Connection connection) {
        try (R2MediaStore store = openClient(connection)) {
            store.verifyWritable();
        }
    }

    private static R2MediaStore openClient(R2Connection connection) {
        try {
            return R2MediaStore.open(connection);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid R2 endpoint \"" + connection.endpoint() + "\": " + e.getMessage());
        }
    }
}
