package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CashTenderTest {

    @Test
    void storesNotesOnASplitWhenTheyCoverTheCashShare() {
        BigDecimal stored = CashTender.toStore(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("400"));

        assertThat(stored).isEqualByComparingTo("1000.00");
        assertThat(CashTender.changeGiven(stored, new BigDecimal("400"))).isEqualByComparingTo("600.00");
    }

    @Test
    void fullCashChangeIsNotesMinusTheBill() {
        BigDecimal stored = CashTender.toStore(new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("700"));

        assertThat(stored).isEqualByComparingTo("1000.00");
        assertThat(CashTender.changeGiven(stored, new BigDecimal("700"))).isEqualByComparingTo("300.00");
    }

    @Test
    void walletOverpayIsNotStoredAsChange() {
        assertThat(CashTender.toStore(new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("1000")))
                .isNull();
        assertThat(CashTender.changeGiven(null, new BigDecimal("1000"))).isNull();
    }

    @Test
    void rejectsNotesThatDoNotCoverTheCashKept() {
        assertThatThrownBy(() ->
                CashTender.toStore(new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("400")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cashReceived must cover the cash taken");
    }
}
