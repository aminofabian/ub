package zelisline.ub.support.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.platform.media.AttachmentStore;
import zelisline.ub.platform.media.CloudinarySignatureService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.realtime.RealtimeTicketController;
import zelisline.ub.platform.realtime.RealtimeTicketService;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.tenancy.repository.BusinessRepository;

class PublicSupportAttachmentTest {

    private static final MockMultipartFile FILE =
            new MockMultipartFile("file", "statement.pdf", "application/pdf", "%PDF-1.7".getBytes());

    private SupportService supportService;
    private CloudinarySignatureService signatureService;
    private AttachmentStore attachmentStore;
    private PublicSupportController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        supportService = mock(SupportService.class);
        signatureService = mock(CloudinarySignatureService.class);
        attachmentStore = mock(AttachmentStore.class);
        ObjectProvider<AttachmentStore> attachments = mock(ObjectProvider.class);
        when(attachments.getIfAvailable()).thenReturn(attachmentStore);
        when(attachments.getObject()).thenReturn(attachmentStore);
        controller = new PublicSupportController(supportService, mock(BusinessRepository.class),
                mock(RealtimeTicketService.class), mock(RealtimeTicketController.class), signatureService, attachments);
    }

    @Test
    void handshakePointsGuestsAtTheBackendWhileR2IsActive() {
        when(attachmentStore.isActive()).thenReturn(true);

        var body = controller.cloudinarySignature(" t1 ", "g", "tok");

        assertThat(body).containsEntry("provider", "r2").containsEntry("folder", "ub/support/t1");
        verify(signatureService, never()).signUpload(anyString(), any());
    }

    @Test
    void handshakeSignsForCloudinaryWhileR2IsOff() {
        when(signatureService.isConfigured()).thenReturn(true);
        when(signatureService.signUpload("ub/support/t1", "auto")).thenReturn(
                new CloudinarySignatureService.SignatureResult("cloud", "key", 1L, "sig", "ub/support/t1", "auto"));

        assertThat(controller.cloudinarySignature("t1", "g", "tok"))
                .containsEntry("provider", "cloudinary").containsEntry("signature", "sig");
    }

    @Test
    void uploadStoresTheFileUnderTheThreadFolder() {
        when(attachmentStore.isActive()).thenReturn(true);
        when(attachmentStore.uploadAttachment(any(), eq("statement.pdf"), eq("ub/support/t1"))).thenReturn(
                new CloudinaryUploadResult("ub/support/t1/a.pdf", "https://m/ub/support/t1/a.pdf",
                        null, null, 8L, "pdf", "application/pdf", null, null, null));

        var body = controller.uploadAttachment("t1", "g", "tok", FILE);

        verify(supportService).requireGuestThreadAccess("t1", "g", "tok");
        assertThat(body).containsEntry("secure_url", "https://m/ub/support/t1/a.pdf");
    }

    @Test
    void uploadChecksThreadAccessBeforeStoringAnything() {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED))
                .when(supportService).requireGuestThreadAccess("t1", "g", "bad");

        assertThatThrownBy(() -> controller.uploadAttachment("t1", "g", "bad", FILE))
                .isInstanceOf(ResponseStatusException.class);
        verify(attachmentStore, never()).uploadAttachment(any(), any(), any());
    }

    @Test
    void uploadIsUnavailableWhileR2IsOff() {
        assertThatThrownBy(() -> controller.uploadAttachment("t1", "g", "tok", FILE))
                .hasMessageContaining("not configured");
    }
}
