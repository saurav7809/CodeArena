package com.codearena.submission;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class LocalJudgeGateway implements JudgeGateway {
    private final RestClient client;
    private final String token;

    LocalJudgeGateway(RestClient.Builder builder, @Value("${arena.judge-url}") String judgeUrl,
                      @Value("${arena.judge-token}") String token) {
        URI uri = URI.create(judgeUrl);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null
            || !List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("The trusted development judge must use a loopback HTTP URL.");
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
        return client.post().uri("/execute").header("X-Judge-Token", token).body(job).retrieve().body(Result.class);
    }
}
