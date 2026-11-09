#!/bin/sh
# Checks that docs/spec.md and the tests agree, test by test. It fails when
#   - a requirement is defined twice, has no row in the spec's test table, or its row names no test;
#   - a test in the table does not exist, is not a test (#[test], @Test, a shell check, a doctest item),
#     sits at another level than the table says, or does not name the requirement next to its
#     definition: in the comment lines directly above it, or in its own name;
#   - a test names a requirement next to its definition but the table does not list it for it;
#   - a test file mentions an ID anywhere else (in a function body, a stray comment), or names an ID
#     that docs/spec.md does not define.
# Test files: src/**/tests.rs, the `mod tests` part and the doctest items of other src files, tests/**
# (Rust and shell), and the Kotlin tests under android/src/test and android/src/androidTest.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec python3 -I - "$root" <<'PYTHON'
import pathlib, re, sys
from collections import Counter, defaultdict

root = pathlib.Path(sys.argv[1])
ID = re.compile(r"\bLU-[A-Z]+-[0-9]+\b")
spec = (root / "docs/spec.md").read_text()
head, _, table = spec.partition("\n## Tests\n")
if not table:
    sys.exit("docs/spec.md has no '## Tests' section")

defined = [m.group(1) for m in re.finditer(r"^\| (LU-[A-Z]+-[0-9]+) \|", head, re.M)]
problems = [f"{name} is defined twice" for name, count in Counter(defined).items() if count > 1]
defined_set = set(defined)

RUST_FN = re.compile(r"^\s*(?:pub(?:\([a-z]+\))?\s+)?fn\s+([a-z_][a-z0-9_]*)\b")
RUST_ITEM = re.compile(r"^\s*(?:pub(?:\([a-z]+\))?\s+)?struct\s+([A-Za-z_][A-Za-z0-9_]*)\b")
KOTLIN_FUN = re.compile(r"^\s*(?:(?:private|internal|public|override|open)\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
SHELL_FN = re.compile(r"^([a-z_][a-z0-9_]*)\(\)\s*\{")
ABOVE = re.compile(r"^\s*(//|#|@|\*|/\*)")


def level_of(relative):
    if relative.startswith("src/"):
        return "unit"
    if relative.startswith("android/src/androidTest/"):
        return "device"
    if relative.startswith("android/src/test/"):
        return "kotlin"
    if relative.endswith(".sh"):
        return "build"
    return "integration"


def test_region(relative, lines):
    """The line numbers of a file that hold tests."""
    if not relative.startswith("src/") or relative.endswith(("/tests.rs", "_tests.rs")):
        return range(len(lines))
    text = "\n".join(lines)
    marker = re.search(r"#\[cfg\(test\)\]\n(?:pub(?:\(crate\))? )?mod tests \{", text)
    start = text[: marker.start()].count("\n") if marker else len(lines)
    region = set(range(start, len(lines)))
    for number, line in enumerate(lines):
        if line.strip() == "#[cfg(doctest)]":
            region.update(range(number, min(number + 4, len(lines))))
    return region


tests = {}      # (file, name) -> {"level": str, "ids": set, "is_test": bool}
stray = []
for path in sorted(
    [*root.glob("src/**/*.rs"), *root.glob("tests/**/*.rs"), *root.glob("tests/**/*.sh"),
     *root.glob("android/src/test/**/*.kt"), *root.glob("android/src/androidTest/**/*.kt")]
):
    if "target" in path.parts or "build" in path.parts:
        continue
    relative = path.relative_to(root).as_posix()
    lines = path.read_text(errors="replace").splitlines()
    region = test_region(relative, lines)
    placed = set()
    for number, line in enumerate(lines):
        if number not in region:
            continue
        if relative.endswith(".rs"):
            match = RUST_FN.match(line) or RUST_ITEM.match(line)
        elif relative.endswith(".kt"):
            match = KOTLIN_FUN.match(line)
        else:
            match = SHELL_FN.match(line)
        if not match:
            continue
        name = match.group(1)
        above, index = [], number - 1
        while index >= 0 and ABOVE.match(lines[index]):
            above.append(lines[index])
            index -= 1
        ids = {i for text in above for i in ID.findall(text)}
        placed.update(range(index + 1, number + 1))
        ids |= {i for i in ID.findall(name.upper().replace("_", "-")) if i in defined_set}
        doc_item = relative.endswith(".rs") and any(a.strip() == "#[cfg(doctest)]" for a in above)
        is_test = (
            relative.endswith(".sh")
            or doc_item
            or any(a.strip() in ("#[test]", "@Test") for a in above)
        )
        level = "doc" if doc_item else level_of(relative)
        tests[(relative, name)] = {"level": level, "ids": ids, "is_test": is_test}
    for number, line in enumerate(lines):
        if ID.search(line) and number not in placed:
            stray.append(f"{relative}:{number + 1} names {', '.join(ID.findall(line))} away from a test definition")
problems += stray

ENTRY = re.compile(r"`([^`]+)::([A-Za-z_][A-Za-z0-9_]*)` \((unit|integration|kotlin|device|build|doc)\)")
traced = defaultdict(list)
rows = set()
for line in table.splitlines():
    match = re.match(r"^\| (LU-[A-Z]+-[0-9]+) \| (.*) \|\s*$", line)
    if not match:
        continue
    requirement, cell = match.groups()
    if requirement in rows:
        problems.append(f"{requirement} has two rows in the test table")
    rows.add(requirement)
    if requirement not in defined_set:
        problems.append(f"the test table names {requirement}, which the spec does not define")
    for relative, name, level in ENTRY.findall(cell):
        traced[requirement].append((relative, name, level))
        found = tests.get((relative, name))
        if found is None:
            problems.append(f"{requirement}: {relative} has no test {name}")
            continue
        if not found["is_test"]:
            problems.append(f"{requirement}: {relative}::{name} is not a test")
        if found["level"] != level:
            problems.append(f"{requirement}: {relative}::{name} is a {found['level']} test, not {level}")
        if requirement not in found["ids"]:
            problems.append(f"{requirement}: {relative}::{name} does not name it next to its definition")

listed = {(requirement, relative, name) for requirement, entries in traced.items() for relative, name, _ in entries}
for (relative, name), found in sorted(tests.items()):
    for requirement in sorted(found["ids"]):
        if requirement not in defined_set:
            problems.append(f"{relative}::{name} names {requirement}, which docs/spec.md does not define")
        elif (requirement, relative, name) not in listed:
            problems.append(f"{relative}::{name} names {requirement}, but the test table does not list it there")
for requirement in defined:
    if not traced.get(requirement):
        problems.append(f"{requirement} has no test in the test table")

areas = Counter(name.split("-")[1] for name in defined)
levels = Counter(level for entries in traced.values() for _, _, level in entries)
print("requirements by area: " + ", ".join(f"{area} {count}" for area, count in sorted(areas.items())))
print(f"requirements: {len(defined)}; test references: {sum(levels.values())} "
      f"({', '.join(f'{level} {count}' for level, count in sorted(levels.items()))})")
if problems:
    print("\n".join("FAIL " + problem for problem in problems))
    sys.exit(f"spec coverage: {len(problems)} problem(s)")
print("spec coverage: every requirement has a test, and every test names its requirements next to its definition")
PYTHON
