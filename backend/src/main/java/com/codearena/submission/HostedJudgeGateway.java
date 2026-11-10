package com.codearena.submission;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

final class HostedJudgeGateway implements JudgeGateway {
    private static final String BATCH = "/submissions/batch";
    private static final String FIELDS = "token,status,stdout,stderr,compile_output,time,memory";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern WORD = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_OUTPUT_BYTES = 32768;
    private final RestClient client;
    private final HostedJudgeSettings settings;
    private final Duration deadline;
    private final Duration pollInterval;

    HostedJudgeGateway(RestClient client, HostedJudgeSettings settings, Duration deadline, Duration pollInterval) {
        this.client = client;
        this.settings = settings;
        this.deadline = deadline;
        this.pollInterval = pollInterval;
    }

    record BatchToken(String token) {}
    record Batch(List<ProviderResult> submissions) {}
    record ProviderStatus(int id, String description) {}
    record ProviderResult(String token, ProviderStatus status, String stdout, String stderr,
                          @JsonProperty("compile_output") String compileOutput, BigDecimal time, Integer memory) {}
    record Output(String text, boolean truncated) {}

    @Override
    public boolean configured() { return true; }

    @Override
    public Result execute(Job job) {
        List<String> tokens = submit(job);
        long expiresAt = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() - expiresAt < 0) {
            List<ProviderResult> results = poll(tokens);
            if (!results.isEmpty() && results.stream().allMatch(result -> result.status().id() >= 3)) {
                return grade(job, results);
            }
            waitForPoll();
        }
        return new Result("INTERNAL_ERROR", 0, null, 0, job.cases().size(), List.of(),
            "The hosted judge did not finish in time. Try again.");
    }

    private List<String> submit(Job job) {
        int language = settings.languageId(job.language());
        double cpuSeconds = job.timeLimitMs() / 1000.0;
        String source = encode(job.code());
        var tests = job.cases().stream().map(test -> Map.of(
            "language_id", language,
            "source_code", source,
            "stdin", encode(test.input()),
            "enable_network", false,
            "cpu_time_limit", cpuSeconds,
            "wall_time_limit", cpuSeconds * 2 + 1,
            "memory_limit", job.memoryLimitMb() * 1024,
            "max_processes_and_or_threads", 48,
            "max_file_size", 128
        )).toList();

        // A failed POST may have accepted jobs already. Never replay it automatically.
        BatchToken[] response = client.post()
            .uri(BATCH + "?base64_encoded=true")
            .body(Map.of("submissions", tests)).retrieve().body(BatchToken[].class);
        if (response == null || response.length != tests.size()) throw invalidResponse();
        var unique = new HashSet<String>();
        var tokens = new ArrayList<String>(response.length);
        for (BatchToken item : response) {
            if (item == null || item.token() == null || !TOKEN.matcher(item.token()).matches() || !unique.add(item.token())) {
                throw invalidResponse();
            }
            tokens.add(item.token());
        }
        return tokens;
    }

    private List<ProviderResult> poll(List<String> tokens) {
        Batch batch;
        try {
            batch = client.get().uri(builder -> builder.path(BATCH)
                .queryParam("tokens", String.join(",", tokens))
                .queryParam("base64_encoded", "true")
                .queryParam("fields", FIELDS).build())
                .retrieve().body(Batch.class);
        } catch (ResourceAccessException error) {
            return List.of();
        } catch (HttpStatusCodeException error) {
            if (error.getStatusCode().is5xxServerError() || error.getStatusCode().value() == 429) return List.of();
            throw error;
        }
        if (batch == null || batch.submissions() == null || batch.submissions().size() != tokens.size()) {
            throw invalidResponse();
        }
        var byToken = new HashMap<String, ProviderResult>();
        for (ProviderResult result : batch.submissions()) {
            if (result == null || result.token() == null || result.status() == null || result.status().id() < 1
                || byToken.putIfAbsent(result.token(), result) != null) throw invalidResponse();
        }
        if (!byToken.keySet().containsAll(tokens)) throw invalidResponse();
        return tokens.stream().map(byToken::get).toList();
    }

    private Result grade(Job job, List<ProviderResult> providerResults) {
        String verdict = "ACCEPTED";
        int passed = 0;
        int maxTime = 0;
        Integer maxMemory = null;
        String diagnostic = "";
        var visible = new ArrayList<CaseResult>();
        for (int index = 0; index < providerResults.size(); index++) {
            Test test = job.cases().get(index);
            ProviderResult result = providerResults.get(index);
            Output stdout = decode(result.stdout());
            Output stderr = decode(result.stderr());
            String status = classify(result, test, job.memoryLimitMb(), stdout, stderr);
            if (status.equals("ACCEPTED")) passed++;
            else if (verdict.equals("ACCEPTED") || status.equals("INTERNAL_ERROR")) verdict = status;
            int time = milliseconds(result.time());
            Integer memory = result.memory() == null ? null : Math.max(0, result.memory());
            maxTime = Math.max(maxTime, time);
            if (memory != null && (maxMemory == null || memory > maxMemory)) maxMemory = memory;
            if (test.visible() && !status.equals("INTERNAL_ERROR")) {
                visible.add(new CaseResult(test.position(), status, clip(stdout.text(), 8192),
                    stdout.truncated() || stderr.truncated() ? "Output exceeded 32 KiB." : clip(stderr.text(), 4096),
                    time, memory));
                if (diagnostic.isEmpty() && status.equals("COMPILATION_ERROR")) {
                    diagnostic = clip(decode(result.compileOutput()).text(), 4096);
                }
            }
        }
        if (verdict.equals("INTERNAL_ERROR")) diagnostic = "The hosted judge could not evaluate every case.";
        if (verdict.equals("COMPILATION_ERROR") && diagnostic.isBlank()) diagnostic = "Compilation failed.";
        return new Result(verdict, maxTime, maxMemory, passed, job.cases().size(), visible, diagnostic);
    }

    private static String classify(ProviderResult result, Test test, int memoryMb, Output stdout, Output stderr) {
        if ("Memory Limit Exceeded".equalsIgnoreCase(result.status().description())) return "MEMORY_LIMIT_EXCEEDED";
        return switch (result.status().id()) {
            case 3 -> stdout.truncated() || stderr.truncated() ? "RUNTIME_ERROR"
                : test.expectedOutput() == null || words(stdout.text()).equals(words(test.expectedOutput()))
                    ? "ACCEPTED" : "WRONG_ANSWER";
            case 4 -> "WRONG_ANSWER";
            case 5 -> "TIME_LIMIT_EXCEEDED";
            case 6 -> "COMPILATION_ERROR";
            case 7, 8, 9, 10, 11, 12 -> {
                boolean reachedLimit = result.memory() != null && result.memory() >= memoryMb * 1024;
                boolean allocationError = stderr.text().contains("OutOfMemoryError") || stderr.text().contains("MemoryError")
                    || stderr.text().contains("std::bad_alloc");
                yield reachedLimit || allocationError ? "MEMORY_LIMIT_EXCEEDED" : "RUNTIME_ERROR";
            }
            default -> "INTERNAL_ERROR";
        };
    }

    private void waitForPoll() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The judge worker was interrupted.");
        }
    }

    private static int milliseconds(BigDecimal time) {
        if (time == null || time.signum() < 0) return 0;
        return time.movePointRight(3).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private static List<String> words(String text) {
        return WORD.matcher(text).results().map(MatchResult::group).toList();
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Output decode(String value) {
        if (value == null) return new Output("", false);
        if (value.length() > 200_000) return new Output("", true);
        byte[] bytes = Base64.getMimeDecoder().decode(value);
        int length = Math.min(bytes.length, MAX_OUTPUT_BYTES);
        return new Output(new String(bytes, 0, length, StandardCharsets.UTF_8), length < bytes.length);
    }

    private static String clip(String text, int length) {
        return text.substring(0, Math.min(text.length(), length));
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException("The hosted judge returned an invalid batch.");
    }
}
