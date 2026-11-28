package zelisline.ub.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExpoPushStyleTest {

    @Test
    void eachShopMomentHasItsOwnChannel() {
        assertEquals(ExpoPushStyle.CHANNEL_SHIFT, ExpoPushStyle.of(NotificationTypes.SHIFT_OPENED).channelId());
        assertEquals(ExpoPushStyle.CHANNEL_SHIFT, ExpoPushStyle.of(NotificationTypes.SHIFT_CLOSED).channelId());
        assertEquals(ExpoPushStyle.CHANNEL_SUPPLY, ExpoPushStyle.of(NotificationTypes.SUPPLY_POSTED).channelId());
        assertEquals(ExpoPushStyle.CHANNEL_MONEY, ExpoPushStyle.of(NotificationTypes.SALES_MILESTONE).channelId());
        assertEquals(ExpoPushStyle.CHANNEL_ORDER, ExpoPushStyle.of(NotificationTypes.STOREFRONT_ORDER_PLACED).channelId());
        assertEquals(ExpoPushStyle.CHANNEL_ALERT, ExpoPushStyle.of(NotificationTypes.SALE_VOIDED).channelId());
        assertEquals("shift.opened", ExpoPushStyle.of(NotificationTypes.SHIFT_OPENED).kind());
    }
}
