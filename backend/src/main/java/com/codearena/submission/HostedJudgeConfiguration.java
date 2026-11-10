package com.codearena.submission;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@EnableConfigurationProperties(HostedJudgeSettings.class)
class HostedJudgeConfiguration {
    @Bean
    JudgeGateway hostedJudgeGateway(RestClient.Builder builder, HostedJudgeSettings settings) {
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
        transport.setReadTimeout(Duration.ofSeconds(8));
        builder.baseUrl(settings.url()).requestFactory(transport);
        switch (settings.auth()) {
            case TOKEN -> builder.defaultHeader("X-Auth-Token", settings.token());
            case RAPIDAPI -> builder
                .defaultHeader("X-RapidAPI-Key", settings.token())
                .defaultHeader("X-RapidAPI-Host", URI.create(settings.url()).getHost());
        }
        return new HostedJudgeGateway(builder.build(), settings, Duration.ofSeconds(80), Duration.ofMillis(750));
    }
}
