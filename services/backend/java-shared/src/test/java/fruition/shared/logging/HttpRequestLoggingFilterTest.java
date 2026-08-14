package fruition.shared.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestLoggingFilterTest {

    private final HttpRequestLoggingFilter filter = new HttpRequestLoggingFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void suppliedRequestIdIsExposedDuringRequestAndReturnedInResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            observed.set(MDC.get("requestId"));
            MDC.put("userId", "user-1");
            MDC.put("flowId", "flow-1");
        });

        assertThat(observed.get()).isEqualTo("request-123");
        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("request-123");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("flowId")).isNull();
    }

    @Test
    void invalidRequestIdIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "invalid request id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo("invalid request id");
        assertThat(MDC.get("requestId")).isNull();
    }
}
