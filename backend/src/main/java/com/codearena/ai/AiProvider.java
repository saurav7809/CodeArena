package com.codearena.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
class AiProvider {
    private static final Logger log = LoggerFactory.getLogger(AiProvider.class);
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final MeterRegistry meters;
    private final Semaphore slots;

    @Autowired
    AiProvider(RestClient.Builder builder, @Value("${arena.ai.api-key}") String apiKey,
               @Value("${arena.ai.model}") String model, @Value("${arena.ai.concurrent}") int concurrent,
               MeterRegistry meters) {
        this(openAiClient(builder), apiKey, model, concurrent, meters);
    }

    AiProvider(RestClient client, String apiKey, String model, int concurrent, MeterRegistry meters) {
        if (concurrent < 1 || concurrent > 8) throw new IllegalArgumentException("Set CODEARENA_AI_CONCURRENT between 1 and 8.");
        this.client = client;
        this.apiKey = apiKey.strip();
        this.model = model.strip();
        this.slots = new Semaphore(concurrent);
        this.meters = meters;
    }

    private static RestClient openAiClient(RestClient.Builder builder) {
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());
        transport.setReadTimeout(Duration.ofSeconds(22));
        return builder.baseUrl("https://api.openai.com/v1").requestFactory(transport).build();
    }

    boolean enabled() { return !apiKey.isEmpty() && !model.isEmpty(); }

    String respond(String action, String instructions, String input, int outputTokens, Runnable reserveBudget) {
        return withSlot(() -> {
            reserveBudget.run();
            String outcome = "error";
            long started = System.nanoTime();
            try {
                String reply = request(instructions, input, outputTokens);
                outcome = "success";
                return reply;
            } finally {
                meters.timer("arena.ai.request", "action", action, "outcome", outcome)
                    .record(Duration.ofNanos(System.nanoTime() - started));
            }
        });
    }

    private String withSlot(Supplier<String> request) {
        if (!slots.tryAcquire()) {
            meters.counter("arena.ai.rejected", "reason", "busy").increment();
            throw new AiBudget.AiLimit("The AI coach is busy. Try again shortly.", "5");
        }
        try { return request.get(); }
        finally { slots.release(); }
    }

    private String request(String instructions, String input, int outputTokens) {
        JsonNode response;
        try {
            response = client.post().uri("/responses")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .body(Map.of("model", model, "instructions", instructions, "input", input,
                    "max_output_tokens", outputTokens, "store", false))
                .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException error) {
            var headers = error.getResponseHeaders();
            String requestId = headers == null ? "" : headers.getFirst("x-request-id");
            if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{1,120}")) requestId = "unavailable";
            log.warn("AI provider returned HTTP {} with request ID {}", error.getStatusCode().value(), requestId);
            throw unavailable();
        } catch (RestClientException error) {
            log.warn("AI provider request failed with {}", error.getClass().getSimpleName());
            throw unavailable();
        }

        if (response == null || !"completed".equals(response.path("status").asString())) {
            log.warn("AI provider did not return a completed response");
            throw unavailable();
        }
        var parts = new ArrayList<String>();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asString()) || !"assistant".equals(item.path("role").asString())) {
                continue;
            }
            for (JsonNode content : item.path("content")) {
                String text = switch (content.path("type").asString()) {
                    case "output_text" -> content.path("text").asString("");
                    case "refusal" -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The AI coach could not answer this request.");
                    default -> "";
                };
                if (!text.isBlank()) parts.add(text.strip());
            }
        }
        if (parts.isEmpty()) throw unavailable();
        String reply = String.join("\n\n", parts);
        if (reply.length() > 16000) throw unavailable();
        return reply;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The AI coach is temporarily unavailable. Try again shortly.");
    }
}
