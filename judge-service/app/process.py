import os
import signal
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from threading import Event, Thread
from time import monotonic
from typing import BinaryIO

from .windows_job import WindowsJob

OUTPUT_LIMIT = 32768
LAUNCHER = Path(__file__).with_name("launch.py").resolve()


@dataclass(frozen=True)
class Result:
    returncode: int
    stdout: str
    stderr: str
    elapsed_ms: int
    memory_kb: int | None
    timed_out: bool
    output_limited: bool


def capture(pipe: BinaryIO, output: bytearray, overflow: Event) -> None:
    with pipe:
        while chunk := pipe.read1(4096):
            remaining = OUTPUT_LIMIT - len(output)
            output.extend(chunk[:remaining])
            if len(chunk) > remaining:
                overflow.set()


def send_input(pipe: BinaryIO, input_text: str) -> None:
    try:
        with pipe:
            pipe.write(b"\x01" + input_text.encode("utf-8"))
    except (BrokenPipeError, OSError):
        pass


def child_env(directory: Path, command: list[str]) -> dict[str, str]:
    env = {key: os.environ[key] for key in ("SYSTEMROOT", "WINDIR", "PATH", "LANG") if key in os.environ}
    env.update({"TEMP": str(directory), "TMP": str(directory), "TMPDIR": str(directory), "PYTHONIOENCODING": "utf-8"})
    env["PATH"] = str(Path(command[0]).parent) + os.pathsep + env.get("PATH", "")
    return env


def run_process(
    command: list[str], directory: Path, input_text: str, timeout_ms: int, memory_mb: int, *, jvm: bool = False
) -> Result:
    job = WindowsJob(memory_mb) if os.name == "nt" else None
    process = None
    threads: list[Thread] = []
    stdout = bytearray()
    stderr = bytearray()
    overflow = Event()
    timed_out = False
    memory_kb = None
    start = monotonic()

    try:
        process = subprocess.Popen(
            [sys.executable, "-I", str(LAUNCHER), str(0 if jvm else memory_mb), *command],
            cwd=directory, env=child_env(directory, command), shell=False,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            start_new_session=os.name != "nt",
        )
        if job:
            job.attach(process.pid)

        threads = [
            Thread(target=capture, args=(process.stdout, stdout, overflow), daemon=True),
            Thread(target=capture, args=(process.stderr, stderr, overflow), daemon=True),
            Thread(target=send_input, args=(process.stdin, input_text), daemon=True),
        ]
        for thread in threads:
            thread.start()

        deadline = start + timeout_ms / 1000
        while process.poll() is None and not overflow.is_set():
            remaining = deadline - monotonic()
            if remaining <= 0:
                timed_out = True
                break
            overflow.wait(min(remaining, 0.02))
    finally:
        elapsed_ms = max(1, int((monotonic() - start) * 1000))
        if job:
            memory_kb = job.stop()
            job.close()
        elif process:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        if process:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
            for thread in threads:
                thread.join(timeout=2)
            if not threads:
                for pipe in (process.stdin, process.stdout, process.stderr):
                    pipe.close()

    return Result(
        process.returncode, stdout.decode("utf-8", errors="replace"), stderr.decode("utf-8", errors="replace"),
        elapsed_ms, memory_kb, timed_out, overflow.is_set(),
    )
