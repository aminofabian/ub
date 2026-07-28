package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class WalletCreditNotificationServiceTest {

    @Test
    void buildMessage_walletOnly_isCompact() {
        String msg = WalletCreditNotificationService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                List.of(),
                2,
                new BigDecimal("130.00"),
                BigDecimal.ZERO,
                new BigDecimal("250.00"),
                BigDecimal.ZERO,
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Mama's: Your purchase change (KES 130) was added to your wallet. Balance: KES 250. https://palmart.co.ke/0714282874",
                msg);
    }

    @Test
    void buildMessage_tabOnly_matchesSmsStyle() {
        String msg = WalletCreditNotificationService.buildMessage(
                "Fabian Amino",
                "Palmart Fresh Foods & Butchery",
                List.of(new CreditSaleReminderLineItem("Kales (Sukuma)", BigDecimal.ONE, new BigDecimal("5.00"))),
                1,
                BigDecimal.ZERO,
                new BigDecimal("45.00"),
                BigDecimal.ZERO,
                new BigDecimal("7970.00"),
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Palmart: Your purchase change (KES 45) reduced your outstanding balance. Now owed: KES 7,970 (was KES 8,015). https://palmart.co.ke/0714282874",
                msg);
    }

    @Test
    void buildMessage_splitTabAndWallet() {
        String msg = WalletCreditNotificationService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                List.of(),
                1,
                new BigDecimal("30.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                new BigDecimal("50.00"),
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Mama's: Your purchase change — KES 100 to tab, KES 30 to wallet. Now owed: KES 50 (was KES 150). Wallet: KES 30. https://palmart.co.ke/0714282874",
                msg);
    }
}
