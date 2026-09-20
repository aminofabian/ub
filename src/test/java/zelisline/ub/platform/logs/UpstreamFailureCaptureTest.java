package zelisline.ub.platform.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A Safaricom callback must be acknowledged with 200 or it is retried, so the failure it
 * carries has to be recorded explicitly to show up as failed in the log feed.
 */
class UpstreamFailureCaptureTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/daraja/stk");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    void captureMarksTheRequestAndStoresDetail() {
        MockHttpServletRequest request = bindRequest();

        PlatformRequestLogErrorCapture.captureUpstreamFailure(
                "mpesa/stk-callback",
                "M-Pesa ResultCode 1032: Request cancelled by user",
                "ResultCode: 1032");

        assertThat(request.getAttribute(PlatformRequestLogErrorCapture.ATTR_UPSTREAM_FAILURE))
                .isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute(PlatformRequestLogErrorCapture.ATTR_TITLE))
                .isEqualTo("M-Pesa ResultCode 1032: Request cancelled by user");
        assertThat(request.getAttribute(PlatformRequestLogErrorCapture.ATTR_TYPE))
                .isEqualTo("mpesa/stk-callback");
        assertThat(request.getAttribute(PlatformRequestLogErrorCapture.ATTR_DETAIL))
                .isEqualTo("ResultCode: 1032");
    }

    @Test
    void titleIsClippedToTheColumnWidth() {
        MockHttpServletRequest request = bindRequest();

        PlatformRequestLogErrorCapture.captureUpstreamFailure(
                "mpesa/stk-callback", "x".repeat(400), "detail");

        String title = (String) request.getAttribute(PlatformRequestLogErrorCapture.ATTR_TITLE);
        assertThat(title).hasSize(256).endsWith("…");
    }

    @Test
    void captureOutsideARequestIsANoOp() {
        RequestContextHolder.resetRequestAttributes();

        PlatformRequestLogErrorCapture.captureUpstreamFailure("mpesa/stk-callback", "t", "d");

        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }
}
