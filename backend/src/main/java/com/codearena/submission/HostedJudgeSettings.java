package com.codearena.submission;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("arena.hosted-judge")
record HostedJudgeSettings(String url, String token, Auth auth, int pythonId, int javaId, int cppId) {
    enum Auth { TOKEN, RAPIDAPI }

    private static final Set<String> PUBLIC_DEMOS = Set.of("ce.judge0.com", "extra-ce.judge0.com", "api.judge0.com",
        "localhost", "127.0.0.1", "[::1]");

    HostedJudgeSettings {
        URI endpoint;
        String invalidUrl = "Set CODEARENA_JUDGE0_URL to a private hosted judge HTTPS endpoint.";
        try {
            endpoint = URI.create(url == null ? "" : url);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(invalidUrl);
        }
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(endpoint.getScheme()) || !host.matches("(?:[a-z0-9-]+\\.)+[a-z][a-z0-9-]*")
            || host.endsWith(".localhost") || PUBLIC_DEMOS.contains(host)
            || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(invalidUrl);
        }
        if (token == null || !token.matches("[\\x21-\\x7E]+") || auth == null) {
            throw new IllegalArgumentException("Set CODEARENA_JUDGE0_TOKEN and a supported judge authentication mode.");
        }
        if (pythonId < 1 || javaId < 1 || cppId < 1 || pythonId == javaId || pythonId == cppId || javaId == cppId) {
            throw new IllegalArgumentException("Set distinct hosted judge language IDs for Python 3, Java 17+, and C++17.");
        }
    }

    int languageId(String language) {
        return switch (language) {
            case "PYTHON" -> pythonId;
            case "JAVA" -> javaId;
            case "CPP" -> cppId;
            default -> throw new IllegalArgumentException("Unsupported submission language.");
        };
    }
}
