package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import zelisline.ub.messaging.application.WhatsAppOrderReplyService.ParsedCommand;
import zelisline.ub.messaging.application.WhatsAppOrderReplyService.ReplyCommand;

class WhatsAppReplyCommandTest {

    @Test
    void parsesVerbAndCode() {
        ParsedCommand confirm = WhatsAppOrderReplyService.parse("CONFIRM 5F6A7B8C");
        assertEquals(ReplyCommand.CONFIRM, confirm.command());
        assertEquals("5F6A7B8C", confirm.code());

        assertEquals(ReplyCommand.READY, WhatsAppOrderReplyService.parse("READY 5F6A7B8C").command());
        assertEquals(ReplyCommand.DISPATCH, WhatsAppOrderReplyService.parse("dispatch 5f6a7b8c").command());
        assertEquals(ReplyCommand.COMPLETE, WhatsAppOrderReplyService.parse("  COMPLETE 5F6A-7B8C  ").command());
    }

    @Test
    void rejectsNonCommands() {
        assertNull(WhatsAppOrderReplyService.parse(null));
        assertNull(WhatsAppOrderReplyService.parse(""));
        assertNull(WhatsAppOrderReplyService.parse("Hi, is the order ready?"));
        assertNull(WhatsAppOrderReplyService.parse("CONFIRM"));            // missing code
        assertNull(WhatsAppOrderReplyService.parse("5F6A7B8C"));            // code without verb
        assertNull(WhatsAppOrderReplyService.parse("CANCEL 5F6A7B8C"));     // unknown verb
        assertNull(WhatsAppOrderReplyService.parse("CONFIRM 123"));         // code too short
    }
}
