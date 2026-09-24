package com.pairforge.api.common;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RequestIdFilterTest {
    @Test void requestContextIsServerGeneratedAndClearedEvenAfterFailure() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "untrusted");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-ID")).isNotEqualTo("untrusted");
            throw new jakarta.servlet.ServletException("fixture");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
    }
}
