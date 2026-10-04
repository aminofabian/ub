package zelisline.ub.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import zelisline.ub.platform.media.ActiveR2ConnectionSource;
import zelisline.ub.platform.media.CloudinarySignatureService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.platform.media.R2Connection;

class MediaControllerTest {

    private static final R2Connection R2 =
            new R2Connection("https://e", "b", "k", "s", "https://media.example.com");

    private CloudinarySignatureService signatureService;
    private MediaStore mediaStore;
    private ActiveR2ConnectionSource r2Source;
    private MediaController controller;

    @BeforeEach
    void setUp() {
        signatureService = mock(CloudinarySignatureService.class);
        mediaStore = mock(MediaStore.class);
        r2Source = mock(ActiveR2ConnectionSource.class);
        when(r2Source.activeR2Connection()).thenReturn(Optional.empty());
        controller = new MediaController(signatureService, mediaStore, r2Source);
    }

    private static MediaController.CloudinarySignatureRequest request(String resourceType) {
        return new MediaController.CloudinarySignatureRequest(" ub/biz/items/1 ", resourceType);
    }

    @Test
    void signsCloudinaryUploadsWhileR2IsOff() {
        when(signatureService.isConfigured()).thenReturn(true);
        when(signatureService.signUpload(anyString(), any())).thenReturn(
                new CloudinarySignatureService.SignatureResult("cloud", "key", 1L, "sig", "ub/biz/items/1", "image"));

        var response = controller.generateSignature(request(null));

        assertThat(response.provider()).isEqualTo("cloudinary");
        assertThat(response.signature()).isEqualTo("sig");
    }

    @Test
    void routesImageUploadsThroughTheBackendWhenR2IsOn() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2));

        var response = controller.generateSignature(request("image"));

        assertThat(response.provider()).isEqualTo("r2");
        assertThat(response.folder()).isEqualTo("ub/biz/items/1");
        assertThat(response.signature()).isNull();
        verify(signatureService, never()).signUpload(anyString(), any());
    }

    @Test
    void keepsNonImageUploadsOnCloudinaryEvenWithR2On() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2));
        when(signatureService.isConfigured()).thenReturn(true);
        when(signatureService.signUpload(anyString(), any())).thenReturn(
                new CloudinarySignatureService.SignatureResult("cloud", "key", 1L, "sig", "ub/support/1", "auto"));

        assertThat(controller.generateSignature(request("auto")).provider()).isEqualTo("cloudinary");
    }

    @Test
    void uploadReturnsCloudinaryShapedJson() {
        when(mediaStore.isConfigured()).thenReturn(true);
        when(mediaStore.uploadImageToFolder(any(), anyString(), anyString(), anyBoolean())).thenReturn(
                new CloudinaryUploadResult("ub/x/a.png", "https://media.example.com/ub/x/a.png",
                        4, 3, 99L, "png", "image/png", null, null, null));

        var body = controller.upload(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}), " ub/x ");

        verify(mediaStore).uploadImageToFolder(any(), anyString(), org.mockito.ArgumentMatchers.eq("ub/x"), anyBoolean());
        assertThat(body).containsEntry("public_id", "ub/x/a.png")
                .containsEntry("secure_url", "https://media.example.com/ub/x/a.png")
                .containsEntry("width", 4)
                .containsEntry("format", "png")
                .containsEntry("resource_type", "image")
                .containsEntry("phash", null);
    }

    @Test
    void uploadRequiresFolderAndConfiguredStore() {
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1});
        assertThatThrownBy(() -> controller.upload(file, " ")).isInstanceOf(ResponseStatusException.class);

        when(mediaStore.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> controller.upload(file, "ub/x")).hasMessageContaining("not configured");
    }
}
