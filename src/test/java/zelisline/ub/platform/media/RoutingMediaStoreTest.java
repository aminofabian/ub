package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RoutingMediaStoreTest {

    private static final R2Connection R2 = new R2Connection("https://e", "b", "k", "s", "https://m");
    private static final byte[] BYTES = {1};

    private ActiveR2ConnectionSource r2Source;
    private CloudinaryImageService cloudinary;
    private ObjectProvider<CloudinaryImageService> cloudinaryProvider;
    private R2MediaStore r2Store;
    private Function<R2Connection, R2MediaStore> factory;
    private RoutingMediaStore routing;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        r2Source = mock(ActiveR2ConnectionSource.class);
        cloudinary = mock(CloudinaryImageService.class);
        cloudinaryProvider = mock(ObjectProvider.class);
        when(cloudinaryProvider.getIfAvailable()).thenReturn(cloudinary);
        r2Store = mock(R2MediaStore.class);
        factory = mock(Function.class);
        when(factory.apply(R2)).thenReturn(r2Store);
        routing = new RoutingMediaStore(r2Source, cloudinaryProvider, factory);
    }

    @Test
    void usesCloudinaryWhileR2IsNotActive() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.empty());

        routing.uploadImageToFolder(BYTES, "a.png", "ub/x", true);
        routing.destroyImage("ub/x/a");

        verify(cloudinary).uploadImageToFolder(BYTES, "a.png", "ub/x", true);
        verify(cloudinary).destroyImage("ub/x/a");
        verify(factory, never()).apply(R2);
    }

    @Test
    void usesR2WhenActiveAndReusesTheClient() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2));

        routing.uploadImage(BYTES, "a.png", "biz", "item");
        routing.uploadFromRemoteUrl("https://x/a.png", "ub/x");
        routing.destroyImage("ub/x/a.png");

        verify(r2Store).uploadImage(BYTES, "a.png", "biz", "item");
        verify(r2Store).uploadFromRemoteUrl("https://x/a.png", "ub/x");
        verify(r2Store).destroyImage("ub/x/a.png");
        verify(factory, times(1)).apply(R2);
        verify(cloudinary, never()).uploadImage(BYTES, "a.png", "biz", "item");
    }

    @Test
    void opensANewClientWhenTheSavedConnectionChanges() {
        R2Connection rotated = new R2Connection("https://e", "b", "k2", "s2", "https://m");
        when(factory.apply(rotated)).thenReturn(mock(R2MediaStore.class));
        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2), Optional.of(rotated));

        routing.uploadImageToFolder(BYTES, "a.png", "ub/x");
        routing.uploadImageToFolder(BYTES, "a.png", "ub/x");

        verify(factory).apply(R2);
        verify(factory).apply(rotated);
    }

    @Test
    void switchingBackToCloudinaryTakesEffectImmediately() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2), Optional.empty());

        routing.uploadImageToFolder(BYTES, "a.png", "ub/x");
        routing.uploadImageToFolder(BYTES, "b.png", "ub/x");

        verify(r2Store).uploadImageToFolder(BYTES, "a.png", "ub/x");
        verify(cloudinary).uploadImageToFolder(BYTES, "b.png", "ub/x");
    }

    @Test
    void reportsConfigurationFromTheActiveStore() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.empty());
        when(cloudinary.isConfigured()).thenReturn(false);
        assertThat(routing.isConfigured()).isFalse();

        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2));
        assertThat(routing.isConfigured()).isTrue();
    }

    @Test
    void sendsAttachmentsToR2OnlyWhileItIsActive() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.of(R2));
        assertThat(routing.isActive()).isTrue();
        routing.uploadAttachment(BYTES, "a.pdf", "ub/support/t");
        verify(r2Store).uploadAttachment(BYTES, "a.pdf", "ub/support/t");

        when(r2Source.activeR2Connection()).thenReturn(Optional.empty());
        assertThat(routing.isActive()).isFalse();
        assertThatThrownBy(() -> routing.uploadAttachment(BYTES, "a.pdf", "ub/support/t"))
                .hasMessageContaining("not on R2");
    }

    @Test
    void failsClearlyWhenNoStoreIsAvailable() {
        when(r2Source.activeR2Connection()).thenReturn(Optional.empty());
        when(cloudinaryProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> routing.uploadImageToFolder(BYTES, "a.png", "ub/x"))
                .hasMessageContaining("not configured");
        routing.destroyImage("ub/x/a");
    }
}
