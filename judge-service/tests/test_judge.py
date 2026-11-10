import os
import sys
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from app.runner import Job, TestCase, evaluate, toolchains


def judge(code, expected, *, language="PYTHON", input_text="", time_ms=2000, extra=None):
    cases = [TestCase(position=1, input=input_text, expectedOutput=expected, visible=True)]
    if extra:
        cases.append(extra)
    return evaluate(Job(language=language, code=code, timeLimitMs=time_ms, memoryLimitMb=128, cases=cases))


class JudgeTests(unittest.TestCase):
    def test_correct_output_and_hidden_case(self):
        hidden = TestCase(position=2, input="40 2", expectedOutput="42", visible=False)
        result = judge("print(sum(map(int, input().split())))", "9", input_text="4 5", extra=hidden)
        self.assertEqual(result.verdict, "ACCEPTED")
        self.assertEqual((result.passedCases, result.totalCases), (2, 2))
        self.assertEqual([case.position for case in result.cases], [1])
        self.assertEqual(result.cases[0].stdout.strip(), "9")
        if os.name == "nt":
            self.assertGreater(result.memoryKb, 0)

    def test_wrong_answer_and_empty_output(self):
        self.assertEqual(judge("pass", "9").verdict, "WRONG_ANSWER")
        self.assertEqual(judge("pass", "").verdict, "ACCEPTED")

    def test_runtime_error(self):
        result = judge("raise ValueError('test failure')", "")
        self.assertEqual(result.verdict, "RUNTIME_ERROR")
        self.assertIn("ValueError", result.cases[0].stderr)

    def test_limit_terminates_child_process(self):
        with TemporaryDirectory() as directory:
            marker = Path(directory) / "late-write"
            child = f"import time; time.sleep(1.4); open({str(marker)!r}, 'w').close()"
            code = f"import subprocess, sys\nsubprocess.Popen([sys.executable, '-c', {child!r}])\nwhile True: pass"
            self.assertEqual(judge(code, "", time_ms=650).verdict, "TIME_LIMIT_EXCEEDED")
            time.sleep(1.5)
            self.assertFalse(marker.exists())

    def test_memory_limit(self):
        self.assertEqual(judge("value = bytearray(256 * 1024 * 1024)", "").verdict, "MEMORY_LIMIT_EXCEEDED")

    def test_output_is_bounded(self):
        result = judge("print('x' * 100000)", "")
        self.assertEqual(result.verdict, "RUNTIME_ERROR")
        self.assertLessEqual(len(result.cases[0].stdout), 8192)

    @unittest.skipUnless(toolchains()["javac"], "Java compiler is required")
    def test_java_compiles_and_runs(self):
        code = "public class Main { public static void main(String[] args) { System.out.println(9); } }"
        self.assertEqual(judge(code, "9", language="JAVA").verdict, "ACCEPTED")
        self.assertEqual(judge("public class Main { syntax error }", "", language="JAVA").verdict, "COMPILATION_ERROR")

    @unittest.skipUnless(toolchains()["cpp"], "C++ compiler is required")
    def test_cpp_compiles_and_runs(self):
        code = "#include <iostream>\nint main() { std::cout << 9; }"
        self.assertEqual(judge(code, "9", language="CPP", time_ms=3000).verdict, "ACCEPTED")
        self.assertEqual(judge("int main( {", "", language="CPP").verdict, "COMPILATION_ERROR")


if __name__ == "__main__":
    unittest.main()
