package com.example.menu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class RequestRateLimitFilterTest {
    @Test void repeatedGuestAddsAreLimitedWhileReadingAndChefWritesStayAvailable() throws Exception {
        var filter = new RequestRateLimitFilter(new ObjectMapper());
        for (int index = 0; index < 60; index++) assertThat(call(filter, "POST", "/api/shopping").getStatus()).isEqualTo(200);
        var blocked = call(filter, "POST", "/api/shopping");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isBetween(1L, 61L);
        assertThat(call(filter, "GET", "/api/shopping").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/chef/shopping").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/submissions").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse call(RequestRateLimitFilter filter, String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
