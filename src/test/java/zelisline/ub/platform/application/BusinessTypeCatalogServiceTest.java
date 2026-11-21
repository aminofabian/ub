package zelisline.ub.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.platform.api.dto.BusinessTypeResponse;
import zelisline.ub.platform.api.dto.UpdateBusinessTypeRequest;
import zelisline.ub.platform.domain.BusinessTypeCatalogEntry;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.platform.repository.BusinessTypeCatalogRepository;

class BusinessTypeCatalogServiceTest {

    private BusinessTypeCatalogRepository repository;
    private MediaStore mediaStore;
    private BusinessTypeCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessTypeCatalogRepository.class);
        mediaStore = mock(MediaStore.class);
        service = new BusinessTypeCatalogService(repository, mediaStore);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CloudinaryUploadResult upload(String publicId, String url) {
        return new CloudinaryUploadResult(publicId, url, null, null, null, null, null, null, null, null);
    }

    @Test
    void listFallsBackToBuiltInDefaultsWhenTableEmpty() {
        when(repository.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of());

        List<BusinessTypeResponse> all = service.list(false);

        // All 20 built-in codes are present, in order, with default copy and no image.
        assertThat(all).hasSize(20);
        assertThat(all.get(0).code()).isEqualTo("butchery");
        BusinessTypeResponse bar = all.stream().filter(t -> t.code().equals("bar")).findFirst().orElseThrow();
        assertThat(bar.label()).isEqualTo("Bar");
        assertThat(bar.section()).isEqualTo("food");
        assertThat(bar.imageUrl()).isNull();
        assertThat(bar.enabled()).isTrue();
        BusinessTypeResponse lodging = all.stream().filter(t -> t.code().equals("lodging")).findFirst().orElseThrow();
        assertThat(lodging.section()).isEqualTo("lodging");
    }

    @Test
    void storedRowOverlaysDefaultAndImageIsReturned() {
        BusinessTypeCatalogEntry row = new BusinessTypeCatalogEntry();
        row.setCode("bar");
        row.setLabel("Sports Bar");
        row.setHint("Pub with a screen");
        row.setSection("food");
        row.setDisplayOrder(170);
        row.setEnabled(true);
        row.setImageUrl("https://cdn.example.com/bar.webp");
        when(repository.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of(row));

        BusinessTypeResponse bar =
                service.list(false).stream().filter(t -> t.code().equals("bar")).findFirst().orElseThrow();

        assertThat(bar.label()).isEqualTo("Sports Bar");
        assertThat(bar.hint()).isEqualTo("Pub with a screen");
        assertThat(bar.imageUrl()).isEqualTo("https://cdn.example.com/bar.webp");
    }

    @Test
    void enabledOnlyHidesDisabledTypes() {
        BusinessTypeCatalogEntry row = new BusinessTypeCatalogEntry();
        row.setCode("spa");
        row.setLabel("Spa");
        row.setSection("service");
        row.setDisplayOrder(110);
        row.setEnabled(false);
        when(repository.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of(row));

        assertThat(service.list(true).stream().anyMatch(t -> t.code().equals("spa"))).isFalse();
        assertThat(service.list(false).stream().anyMatch(t -> t.code().equals("spa"))).isTrue();
    }

    @Test
    void storedRowForUnknownCodeIsIgnored() {
        BusinessTypeCatalogEntry bogus = new BusinessTypeCatalogEntry();
        bogus.setCode("casino");
        bogus.setLabel("Casino");
        bogus.setSection("shop");
        bogus.setDisplayOrder(5);
        bogus.setEnabled(true);
        when(repository.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of(bogus));

        assertThat(service.list(false).stream().anyMatch(t -> t.code().equals("casino"))).isFalse();
        assertThat(service.list(false)).hasSize(20);
    }

    @Test
    void updateRejectsUnknownCode() {
        UpdateBusinessTypeRequest req = new UpdateBusinessTypeRequest("X", null, "shop", 1, true);
        assertThatThrownBy(() -> service.update("casino", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown business type");
        verify(repository, never()).save(any());
    }

    @Test
    void uploadImageStoresUrlAndDestroysPreviousOnReplace() {
        BusinessTypeCatalogEntry row = new BusinessTypeCatalogEntry();
        row.setCode("salon");
        row.setLabel("Salon");
        row.setSection("service");
        row.setDisplayOrder(100);
        row.setEnabled(true);
        row.setImagePublicId("old-public-id");
        when(repository.findById("salon")).thenReturn(Optional.of(row));
        when(mediaStore.isConfigured()).thenReturn(true);
        when(mediaStore.uploadImageToFolder(any(), anyString(), anyString(), eq(false)))
                .thenReturn(upload("new-public-id", "https://cdn.example.com/salon.webp"));

        BusinessTypeResponse out = service.uploadImage("salon", new byte[] {1, 2, 3}, "salon.png");

        assertThat(out.imageUrl()).isEqualTo("https://cdn.example.com/salon.webp");
        verify(mediaStore).destroyImage("old-public-id");
    }

    @Test
    void uploadImageWhenMediaStoreUnconfiguredIsRejected() {
        when(repository.findById("salon")).thenReturn(Optional.empty());
        when(mediaStore.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadImage("salon", new byte[] {1}, "x.png"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Media storage is not configured");
        verify(mediaStore, never()).uploadImageToFolder(any(), anyString(), anyString(), eq(false));
    }

    @Test
    void clearImageDestroysStoredImage() {
        BusinessTypeCatalogEntry row = new BusinessTypeCatalogEntry();
        row.setCode("bar");
        row.setLabel("Bar");
        row.setSection("food");
        row.setDisplayOrder(170);
        row.setEnabled(true);
        row.setImageUrl("https://cdn.example.com/bar.webp");
        row.setImagePublicId("bar-pid");
        when(repository.findById("bar")).thenReturn(Optional.of(row));
        when(mediaStore.isConfigured()).thenReturn(true);

        BusinessTypeResponse out = service.clearImage("bar");

        assertThat(out.imageUrl()).isNull();
        verify(mediaStore).destroyImage("bar-pid");
    }

    @Test
    void backfillCreatesOnlyMissingRows() {
        // "bar" already exists; everything else is missing.
        when(repository.existsById(anyString())).thenReturn(false);
        when(repository.existsById("bar")).thenReturn(true);

        int created = service.backfillMissingDefaults();

        assertThat(created).isEqualTo(18);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(
                e -> e != null && "bar".equals(e.getCode())));
    }
}
