package com.codearena.submission;

import java.util.List;

public interface JudgeGateway {
    record Test(int position, String input, String expectedOutput, boolean visible) {}
    record Spec(int timeLimitMs, int memoryLimitMb, List<Test> cases) {}
    record Job(String language, String code, int timeLimitMs, int memoryLimitMb, List<Test> cases) {}
    record CaseResult(int position, String verdict, String stdout, String stderr, int elapsedMs, Integer memoryKb,
                      String expectedOutput) {
        CaseResult(int position, String verdict, String stdout, String stderr, int elapsedMs, Integer memoryKb) {
            this(position, verdict, stdout, stderr, elapsedMs, memoryKb, null);
        }
    }
    record Result(String verdict, int elapsedMs, Integer memoryKb, int passedCases, int totalCases,
                  List<CaseResult> cases, String diagnostic) {}

    class TemporarilyUnavailable extends RuntimeException {
        TemporarilyUnavailable() { super("The judge deferred the execution request."); }
    }

    boolean configured();
    Result execute(Job job);
}
