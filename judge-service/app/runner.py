import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Literal

from pydantic import BaseModel, Field

from .process import Result, run_process

Language = Literal["JAVA", "PYTHON", "CPP"]
Verdict = Literal["ACCEPTED", "WRONG_ANSWER", "COMPILATION_ERROR", "RUNTIME_ERROR", "TIME_LIMIT_EXCEEDED",
                  "MEMORY_LIMIT_EXCEEDED", "INTERNAL_ERROR"]


class TestCase(BaseModel):
    position: int = Field(ge=1, le=12)
    input: str = Field(max_length=8192)
    expectedOutput: str | None = Field(default=None, max_length=8192)
    visible: bool


class Job(BaseModel):
    language: Language
    code: str = Field(min_length=1, max_length=24000)
    timeLimitMs: int = Field(ge=500, le=5000)
    memoryLimitMb: int = Field(ge=64, le=512)
    cases: list[TestCase] = Field(min_length=1, max_length=12)


class CaseResult(BaseModel):
    position: int
    verdict: Verdict
    stdout: str
    stderr: str
    elapsedMs: int
    memoryKb: int | None


class Judgment(BaseModel):
    verdict: Verdict
    elapsedMs: int = 0
    memoryKb: int | None = None
    passedCases: int = 0
    totalCases: int
    cases: list[CaseResult] = Field(default_factory=list)
    diagnostic: str = ""


@dataclass(frozen=True)
class Program:
    filename: str
    compile_command: list[str]
    run_command: list[str]
    jvm: bool = False


def toolchains() -> dict[str, str | None]:
    return {
        "java": shutil.which("java"),
        "javac": shutil.which("javac"),
        "cpp": shutil.which(os.getenv("CODEARENA_CXX") or "g++") or shutil.which("clang++"),
    }


def program_for(job: Job, directory: Path) -> Program | None:
    if job.language == "PYTHON":
        return Program("main.py", [], [sys.executable, "-I", "main.py"])
    tools = toolchains()
    if job.language == "JAVA" and tools["java"] and tools["javac"]:
        heap_mb = max(16, job.memoryLimitMb - 64)
        return Program(
            "Main.java",
            [tools["javac"], "-J-Xmx128m", "-encoding", "UTF-8", "Main.java"],
            [tools["java"], "-Xms8m", f"-Xmx{heap_mb}m", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=16m",
             "-Xss512k", "-Dfile.encoding=UTF-8", "-cp", ".", "Main"],
            jvm=True,
        )
    if job.language == "CPP" and tools["cpp"]:
        executable = str(directory / ("program.exe" if os.name == "nt" else "program"))
        return Program("main.cpp", [tools["cpp"], "-std=c++17", "-O2", "main.cpp", "-o", executable], [executable])
    return None


def classify(result: Result, expected: str | None, memory_mb: int) -> Verdict:
    if result.timed_out:
        return "TIME_LIMIT_EXCEEDED"
    if result.output_limited:
        return "RUNTIME_ERROR"
    if result.returncode:
        stderr = result.stderr
        memory_error = any(text in stderr for text in ("MemoryError", "OutOfMemoryError", "std::bad_alloc"))
        memory_at_limit = result.memory_kb is not None and result.memory_kb >= memory_mb * 1024 - 1024
        return "MEMORY_LIMIT_EXCEEDED" if memory_error or memory_at_limit else "RUNTIME_ERROR"
    if expected is not None and result.stdout.split() != expected.split():
        return "WRONG_ANSWER"
    return "ACCEPTED"


def evaluate(job: Job) -> Judgment:
    total = len(job.cases)
    with TemporaryDirectory(prefix="codearena-job-") as workspace:
        directory = Path(workspace)
        program = program_for(job, directory)
        if not program:
            return Judgment(verdict="INTERNAL_ERROR", totalCases=total, diagnostic="The selected compiler is unavailable.")

        (directory / program.filename).write_text(job.code, encoding="utf-8")
        if program.compile_command:
            compiled = run_process(program.compile_command, directory, "", 15000, 384, jvm=program.jvm)
            if compiled.timed_out or compiled.output_limited or compiled.returncode:
                diagnostic = "Compilation exceeded its time limit." if compiled.timed_out else compiled.stderr
                return Judgment(verdict="COMPILATION_ERROR", totalCases=total,
                                diagnostic=(diagnostic or "Compilation failed.")[:4096])

        visible: list[CaseResult] = []
        verdicts: list[Verdict] = []
        maximum_time = 0
        memory_peaks: list[int] = []
        for case in job.cases:
            result = run_process(program.run_command, directory, case.input, job.timeLimitMs, job.memoryLimitMb, jvm=program.jvm)
            verdict = classify(result, case.expectedOutput, job.memoryLimitMb)
            verdicts.append(verdict)
            maximum_time = max(maximum_time, result.elapsed_ms)
            if result.memory_kb is not None:
                memory_peaks.append(result.memory_kb)
            if case.visible:
                visible.append(CaseResult(
                    position=case.position, verdict=verdict, stdout=result.stdout[:8192],
                    stderr="Output exceeded 32 KiB." if result.output_limited else result.stderr[:4096],
                    elapsedMs=result.elapsed_ms, memoryKb=result.memory_kb,
                ))

        final_verdict = next((status for status in verdicts if status != "ACCEPTED"), "ACCEPTED")
        return Judgment(
            verdict=final_verdict, elapsedMs=maximum_time, memoryKb=max(memory_peaks, default=None),
            passedCases=verdicts.count("ACCEPTED"), totalCases=total, cases=visible,
        )
