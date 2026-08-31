package zelisline.ub.messages.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;

/**
 * Desktop reply path: instead of sending through providers (the till has none —
 * they are blanked in {@code application-desktop.properties}), the reply is
 * persisted with {@code outcome=queued} for the cloud relay, while keeping the
 * same contact-field validation the cloud sender enforces.
 */
class DesktopQueuedContactReplySenderTest {

    private final ContactMessageReplyRepository repository = mock(ContactMessageReplyRepository.class);
    private final DesktopQueuedContactReplySender sender =
        new DesktopQueuedContactReplySender(repository);

    @BeforeEach
    void setUp() {
        when(repository.save(any(ContactMessageReply.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ContactMessage message(String email, String phone) {
        ContactMessage m = new ContactMessage();
        m.setId("m-1");
        m.setName("Jane Shopper");
        m.setEmail(email);
        m.setPhone(phone);
        m.setBody("Do you stock maize flour?");
        return m;
    }

    @Test
    void emailReplyIsQueuedNotSent() {
        ContactMessageReply saved = sender.send(
            message("jane@example.com", null),
            new ContactMessageReplyRequest(ContactReplyChannel.EMAIL, "  Yes we do! "),
            "till-user-1", "Palmart", false, "r-1");

        verify(repository).save(any(ContactMessageReply.class));
        assertEquals("r-1", saved.getId());
        assertEquals("m-1", saved.getContactMessageId());
        assertEquals(ContactReplyChannel.EMAIL, saved.getChannel());
        assertEquals("Yes we do!", saved.getBody());
        assertEquals("queued", saved.getOutcome());
        assertEquals("Will send when the till is online", saved.getDetail());
        assertEquals("till-user-1", saved.getSentByUserId());
    }

    @Test
    void emailReplyWithoutEmailIsRejectedLikeTheCloudSender() {
        assertThrows(ResponseStatusException.class, () ->
            sender.send(
                message(null, null),
                new ContactMessageReplyRequest(ContactReplyChannel.EMAIL, "Hello"),
                "till-user-1", "Palmart", false, null));
        verify(repository, never()).save(any(ContactMessageReply.class));
    }

    @Test
    void smsReplyWithoutPhoneIsRejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            sender.send(
                message("jane@example.com", null),
                new ContactMessageReplyRequest(ContactReplyChannel.SMS, "Hello"),
                "till-user-1", "Palmart", false, null));
        assertTrue(ex.getStatusCode().value() == 400);
        verify(repository, never()).save(any(ContactMessageReply.class));
    }

    @Test
    void whatsappReplyWithPhoneQueues() {
        ContactMessageReply saved = sender.send(
            message("jane@example.com", "0712345678"),
            new ContactMessageReplyRequest(ContactReplyChannel.WHATSAPP, "On it"),
            "till-user-1", "Palmart", false, null);

        assertEquals("queued", saved.getOutcome());
        assertEquals(ContactReplyChannel.WHATSAPP, saved.getChannel());
    }
}
