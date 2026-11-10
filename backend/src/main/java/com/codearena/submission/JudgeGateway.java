package com.codearena.submission;

import java.util.List;

public interface JudgeGateway {
    record Test(int position, String input, String expectedOutput, boolean visible) {}
    record Spec(int timeLimitMs, int memoryLimitMb, List<Test> cases) {}
    record Job(String language, String code, int timeLimitMs, int memoryLimitMb, List<Test> cases) {}
    record CaseResult(int position, String verdict, String stdout, String stderr, int elapsedMs, Integer memoryKb) {}
    record Result(String verdict, int elapsedMs, Integer memoryKb, int passedCases, int totalCases,
                  List<CaseResult> cases, String diagnostic) {}

    boolean configured();
    Result execute(Job job);
}
