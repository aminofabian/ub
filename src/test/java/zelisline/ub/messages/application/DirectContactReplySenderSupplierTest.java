package zelisline.ub.messages.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.marketplace.application.SupplierPortalMessagesService;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;

@ExtendWith(MockitoExtension.class)
class DirectContactReplySenderSupplierTest {

    @Mock
    private ContactMessageReplyRepository contactMessageReplyRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private CustomerMessageDispatcher customerMessageDispatcher;
    @Mock
    private BusinessCreditMessagingSettingsService messagingSettingsService;
    @Mock
    private SupplierPortalMessagesService supplierPortalMessagesService;

    @InjectMocks
    private DirectContactReplySender sender;

    @Test
    void routesSyntheticSupplierEmailIntoThePortal() {
        ContactMessage message = supplierMessage("supplier-portal@abc12345.local");
        when(supplierPortalMessagesService.recordShopReplyForContact(
                "m1", "Shop", "Thanks for the delivery")).thenReturn(true);
        when(contactMessageReplyRepository.save(any(ContactMessageReply.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ContactMessageReply reply = sender.send(
                message,
                new ContactMessageReplyRequest(ContactReplyChannel.EMAIL, "Thanks for the delivery"),
                "user-1",
                "Shop",
                false,
                null);

        assertThat(reply.getOutcome()).isEqualTo("sent");
        assertThat(reply.getDetail()).isEqualTo("supplier-portal");
        assertThat(reply.getChannel()).isEqualTo(ContactReplyChannel.EMAIL);
        verify(notificationService, never())
                .sendContactReplyEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToEmailWhenNoSupplierThreadExists() {
        ContactMessage message = supplierMessage("supplier-portal@abc12345.local");
        when(supplierPortalMessagesService.recordShopReplyForContact(
                "m1", "Shop", "Thanks")).thenReturn(false);
        when(contactMessageReplyRepository.save(any(ContactMessageReply.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ContactMessageReply reply = sender.send(
                message,
                new ContactMessageReplyRequest(ContactReplyChannel.EMAIL, "Thanks"),
                "user-1",
                "Shop",
                false,
                null);

        assertThat(reply.getOutcome()).isEqualTo("sent");
        assertThat(reply.getDetail()).isEqualTo("email");
        verify(notificationService)
                .sendContactReplyEmail(message.getEmail(), "Re: your message to Shop", "Thanks", "Shop");
    }

    private static ContactMessage supplierMessage(String email) {
        ContactMessage message = new ContactMessage();
        message.setId("m1");
        message.setBusinessId("b1");
        message.setName("Supplier");
        message.setEmail(email);
        message.setBody("[Supplier portal message]\nHello");
        return message;
    }
}
