#!/usr/bin/env python3
"""Fixed stdin/stdout contract for the isolated Python sandbox container."""

import io
import json
import sys
from types import MappingProxyType


MAX_REQUEST_BYTES = 96 * 1024
MAX_CODE_BYTES = 20 * 1024
MAX_OUTPUT_BYTES = 64 * 1024


class OutputLimitExceeded(Exception):
    """Abort user code on the first oversized write instead of accumulating discarded chunks."""


class OutputBudget:
    def __init__(self, limit):
        self._remaining = limit
        self.exceeded = False

    def accept(self, value):
        encoded = value.encode("utf-8", errors="replace")
        if len(encoded) <= self._remaining:
            self._remaining -= len(encoded)
            return value
        self.exceeded = True
        raise OutputLimitExceeded()


class BoundedTextBuffer(io.TextIOBase):
    def __init__(self, budget):
        self._budget = budget
        self._parts = []

    @property
    def encoding(self):
        return "utf-8"

    def writable(self):
        return True

    def write(self, value):
        text = str(value)
        accepted = self._budget.accept(text)
        if accepted:
            self._parts.append(accepted)
        return len(text)

    def flush(self):
        return None

    def value(self):
        return "".join(self._parts)


def freeze(value):
    if isinstance(value, dict):
        return MappingProxyType({key: freeze(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(freeze(item) for item in value)
    return value


def read_request():
    raw = sys.stdin.buffer.read(MAX_REQUEST_BYTES + 1)
    if len(raw) > MAX_REQUEST_BYTES:
        return None
    try:
        request = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(request, dict):
        return None
    code = request.get("code")
    input_value = request.get("input")
    if not isinstance(code, str) or not isinstance(input_value, dict):
        return None
    if len(code.encode("utf-8")) > MAX_CODE_BYTES:
        return None
    return code, input_value


def execute():
    request = read_request()
    if request is None:
        return failed("INVALID_REQUEST")
    code, input_value = request
    try:
        compiled = compile(code, "<sandbox>", "exec")
    except SyntaxError:
        return failed("PYTHON_SYNTAX_ERROR")

    budget = OutputBudget(MAX_OUTPUT_BYTES)
    captured_stdout = BoundedTextBuffer(budget)
    captured_stderr = BoundedTextBuffer(budget)
    original_stdout = sys.stdout
    original_stderr = sys.stderr
    try:
        sys.stdout = captured_stdout
        sys.stderr = captured_stderr
        globals_scope = {"__name__": "__main__", "input_data": freeze(input_value)}
        exec(compiled, globals_scope, globals_scope)
    except OutputLimitExceeded:
        return failed("OUTPUT_LIMIT_EXCEEDED")
    except BaseException:
        if budget.exceeded:
            return failed("OUTPUT_LIMIT_EXCEEDED")
        return failed("PYTHON_RUNTIME_ERROR", captured_stdout.value(), captured_stderr.value())
    finally:
        sys.stdout = original_stdout
        sys.stderr = original_stderr

    if budget.exceeded:
        return failed("OUTPUT_LIMIT_EXCEEDED")
    return {
        "status": "SUCCEEDED",
        "stdout": captured_stdout.value(),
        "stderr": captured_stderr.value(),
        "errorCode": None,
    }


def failed(error_code, stdout="", stderr=""):
    return {
        "status": "FAILED",
        "stdout": stdout,
        "stderr": stderr,
        "errorCode": error_code,
    }


def main():
    response = execute()
    sys.__stdout__.write(json.dumps(response, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.__stdout__.flush()


if __name__ == "__main__":
    main()
