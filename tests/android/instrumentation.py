"""Reads the raw output of `am instrument -r` (tests/android/run.sh).

    instrumentation.py RUN.txt              Prints each test that did not pass and the number that did;
                                            fails unless the run finished and every test passed: a
                                            failure, an error, an @Ignore or a failed assumption (status
                                            -4, a skip) all fail it.
    instrumentation.py --spec SPEC RUN...   Fails unless every device test that SPEC's table lists
                                            passed in one of the runs.
"""

import re
import sys

STATUS = {0: "passed", -1: "error", -2: "failed", -3: "ignored", -4: "skipped by an assumption"}


def results(path):
    """The (class, test, code, stack) of each finished test, and whether the run finished."""
    finished, tests, current, key = False, [], {}, None
    for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code != 1:
                tests.append((current.get("class", ""), current.get("test", ""), code, current.get("stack", "")))
            current, key = {}, None
        elif line.startswith("INSTRUMENTATION_CODE: "):
            finished = line.strip() == "INSTRUMENTATION_CODE: -1"
            key = None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if "shortMsg=" in line:
                print(line, file=sys.stderr)
            key = None
        elif key is not None:
            current[key] += "\n" + line
    return tests, finished


def check_run(path):
    tests, finished = results(path)
    bad = [test for test in tests if test[2] != 0]
    for klass, name, code, stack in bad:
        print(f"{klass.rsplit('.', 1)[-1]}.{name}: {STATUS.get(code, code)}", file=sys.stderr)
        if stack:
            print("\n".join(stack.splitlines()[:6]), file=sys.stderr)
    if not finished:
        sys.exit(f"{path}: the instrumentation did not finish")
    if bad or not tests:
        sys.exit(f"{path}: {len(bad)} of {len(tests)} tests did not pass")
    print(len(tests))


def check_spec(spec, runs):
    table = open(spec, encoding="utf-8").read().partition("\n## Tests\n")[2]
    listed = set(re.findall(r"`android/src/androidTest/[^`]*/([A-Za-z]+)\.kt::([A-Za-z0-9_]+)` \(device\)", table))
    passed = {
        (klass.rsplit(".", 1)[-1], name)
        for run in runs
        for klass, name, code, _ in results(run)[0]
        if code == 0
    }
    missing = sorted(listed - passed)
    for klass, name in missing:
        print(f"{klass}.{name} is in docs/spec.md but did not pass on the device", file=sys.stderr)
    if missing or not listed:
        sys.exit(f"{len(missing)} of {len(listed)} device tests of the spec did not pass")
    print(f"every one of the spec's {len(listed)} device tests passed")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--spec"]:
        check_spec(sys.argv[2], sys.argv[3:])
    else:
        check_run(sys.argv[1])
