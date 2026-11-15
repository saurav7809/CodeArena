package com.codearena.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ApiRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 768 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/") && !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", id);
        response.setHeader("Cache-Control", "no-store");
        try (var ignored = MDC.putCloseable("requestId", id)) {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                response.setStatus(413);
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"The request body is too large.\"}");
                return;
            }
            chain.doFilter(request.getContentLengthLong() < 0 ? new LimitedRequest(request) : request, response);
        }
    }

    static final class BodyTooLarge extends IOException {}

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request) { super(request); }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                var source = super.getInputStream();
                stream = new ServletInputStream() {
                    private int count;

                    @Override public boolean isFinished() { return source.isFinished(); }
                    @Override public boolean isReady() { return source.isReady(); }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) {
                        source.setReadListener(listener);
                    }

                    @Override public int read() throws IOException {
                        int value = source.read();
                        if (value != -1 && ++count > MAX_BODY_BYTES) throw new BodyTooLarge();
                        return value;
                    }

                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (length == 0) return 0;
                        int read = source.read(bytes, offset, Math.min(length, Math.max(1, MAX_BODY_BYTES - count + 1)));
                        if (read > 0 && (count += read) > MAX_BODY_BYTES) throw new BodyTooLarge();
                        return read;
                    }
                };
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }
}
