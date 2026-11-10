package com.codearena.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthThrottleTest {
    @Test
    void capsRegistrationWithoutConsumingTheLoginBudget() throws Exception {
        var throttle = new AuthThrottle();
        for (int i = 0; i < 12; i++) {
            assertThat(request(throttle, "/api/auth/register").getStatus()).isEqualTo(200);
        }
        var rejected = request(throttle, "/api/auth/register");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(request(throttle, "/api/auth/login").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse request(AuthThrottle throttle, String path) throws Exception {
        var response = new MockHttpServletResponse();
        throttle.doFilter(new MockHttpServletRequest("POST", path), response, new MockFilterChain());
        return response;
    }
}
