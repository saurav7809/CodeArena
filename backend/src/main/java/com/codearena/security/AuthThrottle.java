package com.codearena.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.web.filter.OncePerRequestFilter;

final class AuthThrottle extends OncePerRequestFilter {
    private static final String REGISTER = "/api/auth/register";
    private static final String LOGIN = "/api/auth/login";
    private final Window registrations = new Window(12);
    private final Window logins = new Window(60);
    private final Semaphore hashingSlots = new Semaphore(3);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || (!request.getRequestURI().equals(REGISTER)
            && !request.getRequestURI().equals(LOGIN));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!hashingSlots.tryAcquire()) {
            reject(response, "1");
            return;
        }
        try {
            Window window = request.getRequestURI().equals(REGISTER) ? registrations : logins;
            if (window.take()) chain.doFilter(request, response);
            else reject(response, "60");
        } finally {
            hashingSlots.release();
        }
    }

    private static void reject(HttpServletResponse response, String retryAfter) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", retryAfter);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"Too many authentication requests. Try again shortly.\"}");
    }

    private static final class Window {
        private static final long LENGTH = Duration.ofMinutes(1).toNanos();
        private final int limit;
        private long opened = System.nanoTime();
        private int used;

        Window(int limit) { this.limit = limit; }

        synchronized boolean take() {
            long now = System.nanoTime();
            if (now - opened >= LENGTH) {
                opened = now;
                used = 0;
            }
            if (used >= limit) return false;
            used++;
            return true;
        }
    }
}
