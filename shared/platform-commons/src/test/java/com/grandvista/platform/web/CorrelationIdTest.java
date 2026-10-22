package com.grandvista.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdTest {

    @Test
    void acceptsWellFormedIdsOnly() {
        assertThat(CorrelationId.isValid("3f2b8c1e-aaaa-bbbb-cccc-1234567890ab")).isTrue();
        assertThat(CorrelationId.isValid("short")).isFalse();
        assertThat(CorrelationId.isValid("abc\r\nInjected: header")).isFalse();
        assertThat(CorrelationId.isValid(null)).isFalse();
    }

    @Test
    void filterEchoesValidIdAndReplacesInvalidOne() throws Exception {
        var filter = new CorrelationIdFilter();

        var valid = new MockHttpServletRequest();
        valid.addHeader(CorrelationId.HEADER, "client-supplied-id-123");
        var validResponse = new MockHttpServletResponse();
        filter.doFilter(valid, validResponse, new MockFilterChain());
        assertThat(validResponse.getHeader(CorrelationId.HEADER)).isEqualTo("client-supplied-id-123");

        var invalid = new MockHttpServletRequest();
        invalid.addHeader(CorrelationId.HEADER, "<script>");
        var invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, new MockFilterChain());
        assertThat(invalidResponse.getHeader(CorrelationId.HEADER)).isNotEqualTo("<script>").hasSize(36);
    }
}
