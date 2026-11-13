package zelisline.ub.credits.sms.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.credits.sms.application.SmsInboundService.Keyword;
import zelisline.ub.messaging.domain.SmsMessage;

class SmsInboundKeywordTest {

    @Test
    void stopWordsInEnglishAndSwahili() {
        assertThat(SmsInboundService.keyword("STOP")).isEqualTo(Keyword.STOP);
        assertThat(SmsInboundService.keyword(" stop. ")).isEqualTo(Keyword.STOP);
        assertThat(SmsInboundService.keyword("Ondoka tafadhali")).isEqualTo(Keyword.STOP);
        assertThat(SmsInboundService.keyword("toka")).isEqualTo(Keyword.STOP);
    }

    @Test
    void joinWords() {
        assertThat(SmsInboundService.keyword("JOIN")).isEqualTo(Keyword.JOIN);
        assertThat(SmsInboundService.keyword("yes please")).isEqualTo(Keyword.JOIN);
        assertThat(SmsInboundService.keyword("Ndiyo")).isEqualTo(Keyword.JOIN);
    }

    @Test
    void anythingElseIsIgnored() {
        assertThat(SmsInboundService.keyword("Please stop by tomorrow")).isEqualTo(Keyword.NONE);
        assertThat(SmsInboundService.keyword("")).isEqualTo(Keyword.NONE);
        assertThat(SmsInboundService.keyword(null)).isEqualTo(Keyword.NONE);
    }

    @Test
    void deliveryWordsMapToFinalStatuses() {
        assertThat(SmsInboundService.deliveryStatus("Delivered")).isEqualTo(SmsMessage.STATUS_DELIVERED);
        assertThat(SmsInboundService.deliveryStatus("DeliveredToTerminal")).isEqualTo(SmsMessage.STATUS_DELIVERED);
        assertThat(SmsInboundService.deliveryStatus("UNDELIVERED")).isEqualTo(SmsMessage.STATUS_FAILED);
        assertThat(SmsInboundService.deliveryStatus("Rejected")).isEqualTo(SmsMessage.STATUS_FAILED);
        assertThat(SmsInboundService.deliveryStatus("Sent")).isNull();
        assertThat(SmsInboundService.deliveryStatus("queued")).isNull();
    }
}
