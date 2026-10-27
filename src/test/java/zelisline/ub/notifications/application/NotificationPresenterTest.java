package zelisline.ub.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationPresenterTest {

    private final NotificationPresenter presenter = new NotificationPresenter();

    @Test
    void fallsBackToTheTypeLabelWhenThereIsNoPayload() {
        NotificationPresenter.Presentation copy =
                presenter.present("payable.overdue", null);

        assertThat(copy.title()).isEqualTo("Overdue supplier payments");
        assertThat(copy.body()).isEmpty();
        assertThat(copy.actionUrl()).isEmpty();
    }

    @Test
    void prefersTheAuthoredCopyInThePayload() {
        String payload = """
                {"title":"New web order","body":"Elias paid KES 1,200","actionUrl":"/storefront/web-orders/abc"}
                """;

        NotificationPresenter.Presentation copy =
                presenter.present("storefront.order.placed", payload);

        assertThat(copy.title()).isEqualTo("New web order");
        assertThat(copy.body()).isEqualTo("Elias paid KES 1,200");
        assertThat(copy.actionUrl()).isEqualTo("/storefront/web-orders/abc");
    }

    @Test
    void fallsBackToPaymentUrlWhenActionUrlIsMissing() {
        String payload = "{\"title\":\"Web order paid\",\"paymentUrl\":\"https://pay.example/1\"}";

        NotificationPresenter.Presentation copy =
                presenter.present("storefront.order.paid", payload);

        assertThat(copy.actionUrl()).isEqualTo("https://pay.example/1");
    }

    @Test
    void aMalformedPayloadKeepsTheTypeLabel() {
        NotificationPresenter.Presentation copy =
                presenter.present("stock.low", "{not json");

        assertThat(copy.title()).isEqualTo("Low stock alert");
        assertThat(copy.body()).isEmpty();
    }

    @Test
    void typesWithoutAuthoredCopyIgnorePayloadText() {
        // Nothing renders a body for this type, so a stray payload body must not
        // leak into the bell as copy the UI never asked for.
        String payload = "{\"body\":\"should not appear\",\"title\":\"Overdue supplier payments\"}";

        NotificationPresenter.Presentation copy = presenter.present("payable.overdue", payload);

        assertThat(copy.body()).isEmpty();
        assertThat(copy.title()).isEqualTo("Overdue supplier payments");
    }
}
