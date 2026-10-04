package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class R2MediaStoreTest {

    private S3Client client;
    private RemoteImageFetcher fetcher;
    private R2MediaStore store;

    @BeforeEach
    void setUp() {
        R2Connection connection = new R2Connection(
                "https://acct.r2.cloudflarestorage.com", "picshare-media", "key", "secret",
                "https://media.example.com");
        client = mock(S3Client.class);
        fetcher = mock(RemoteImageFetcher.class);
        store = new R2MediaStore(connection, client, fetcher);
    }

    private static byte[] png(int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void uploadsItemImageUnderItemFolderWithDetectedFormat() throws IOException {
        byte[] bytes = png(3, 2);

        CloudinaryUploadResult result = store.uploadImage(bytes, "photo.jpg", "biz1", "item9");

        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("picshare-media");
        assertThat(request.getValue().key()).matches("ub/biz1/items/item9/[0-9a-f-]{36}\\.png");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(result.publicId()).isEqualTo(request.getValue().key());
        assertThat(result.secureUrl()).isEqualTo("https://media.example.com/" + result.publicId());
        assertThat(result.width()).isEqualTo(3);
        assertThat(result.height()).isEqualTo(2);
        assertThat(result.bytes()).isEqualTo(bytes.length);
        assertThat(result.format()).isEqualTo("png");
        assertThat(result.phash()).isNull();
    }

    @Test
    void rejectsContentThatIsNotAnImage() {
        assertThatThrownBy(() -> store.uploadImageToFolder("<html>".getBytes(), "x.png", "ub/misc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported image format");
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> store.uploadImageToFolder(new byte[0], "x.png", "ub/misc"))
                .hasMessageContaining("Empty image file");
        assertThatThrownBy(() -> store.uploadImageToFolder(new byte[12 * 1024 * 1024 + 1], "x.png", "ub/misc"))
                .hasMessageContaining("size limit");
    }

    @Test
    void wrapsStorageFailuresAsBadGateway() throws IOException {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("AccessDenied").build());

        assertThatThrownBy(() -> store.uploadImageToFolder(png(1, 1), "x.png", "ub/misc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Could not store image in R2");
    }

    @Test
    void normalizesFoldersAndRefusesTraversal() {
        assertThat(R2MediaStore.normalizeFolder(null)).isEqualTo("ub/misc");
        assertThat(R2MediaStore.normalizeFolder("/ub//biz/logo/")).isEqualTo("ub/biz/logo");
        assertThatThrownBy(() -> R2MediaStore.normalizeFolder("ub/../secrets"))
                .hasMessageContaining("Invalid media folder");
    }

    @Test
    void reHostsRemoteImagesThroughTheFetcher() throws IOException {
        when(fetcher.fetch("https://example.com/a.png")).thenReturn(png(1, 1));

        CloudinaryUploadResult result = store.uploadFromRemoteUrl("https://example.com/a.png", "global-catalog/p1");

        assertThat(result.publicId()).startsWith("global-catalog/p1/").endsWith(".png");
    }

    @Test
    void destroysExactKeyAndMirroredCloudinaryCopies() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(List.of(
                        S3Object.builder().key("ub/biz/items/1/abc.jpg").build(),
                        S3Object.builder().key("ub/biz/items/1/abc.d/nested.jpg").build()))
                .build());

        store.destroyImage("ub/biz/items/1/abc");

        var deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client, org.mockito.Mockito.times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly("ub/biz/items/1/abc", "ub/biz/items/1/abc.jpg");
    }

    @Test
    void verifyWritableWritesThenDeletesAProbe() {
        store.verifyWritable();

        var put = ArgumentCaptor.forClass(PutObjectRequest.class);
        var delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).putObject(put.capture(), any(RequestBody.class));
        verify(client).deleteObject(delete.capture());
        assertThat(put.getValue().key()).startsWith("_palmart-healthcheck/");
        assertThat(delete.getValue().key()).isEqualTo(put.getValue().key());
    }

    @Test
    void verifyWritableReportsTheProviderReason() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("InvalidAccessKeyId").build());

        assertThatThrownBy(() -> store.verifyWritable())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("InvalidAccessKeyId");
    }

    @Test
    void destroySwallowsStorageErrors() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        store.destroyImage("ub/biz/items/1/abc.png");
        store.destroyImage("  ");
    }
}
