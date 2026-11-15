package com.codearena.submission;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("!prod")
class LocalJudgeGateway implements JudgeGateway {
    private static final List<String> LOOPBACK = List.of("127.0.0.1", "localhost", "::1", "[::1]");
    private final RestClient client;
    private final String token;

    LocalJudgeGateway(RestClient.Builder builder, @Value("${arena.judge-url}") String judgeUrl,
                      @Value("${arena.judge-token}") String token,
                      @Value("${server.address}") String serverAddress, Environment environment) {
        URI uri = URI.create(judgeUrl);
        boolean compose = environment.acceptsProfiles(Profiles.of("compose"));
        boolean allowedHost = compose ? "judge".equals(uri.getHost())
            : uri.getHost() != null && LOOPBACK.contains(uri.getHost()) && LOOPBACK.contains(serverAddress);
        if (!"http".equals(uri.getScheme()) || !allowedHost || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(compose ? "The Compose judge URL must use the internal judge hostname."
                : "The trusted development judge requires a loopback API bind and judge URL.");
        }
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build());
        transport.setReadTimeout(Duration.ofSeconds(100));
        this.client = builder.baseUrl(judgeUrl).requestFactory(transport).build();
        this.token = token;
    }

    @Override
    public boolean configured() { return token.length() >= 32; }

    @Override
    public Result execute(Job job) {
        try {
            return client.post().uri("/execute").header("X-Judge-Token", token).body(job).retrieve().body(Result.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 429) throw new TemporarilyUnavailable();
            throw error;
        }
    }
}
