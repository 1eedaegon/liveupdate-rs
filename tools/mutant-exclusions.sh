#!/bin/sh
# The mutants that .cargo/mutants.toml leaves out by `file:line:column` are where it says. A change above
# one of them moves it, and the exclusion then leaves out another mutant or none: the full run of a release
# commit finds it missed, and the release stops without a tag. Each exclusion must match exactly one mutant
# of cargo-mutants' own list, read without the configuration, and that mutant's line must hold the code that
# the exclusion's reason is about.
#
#   sh tools/mutant-exclusions.sh        (needs cargo-mutants)
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
listed=$(mktemp "${TMPDIR:-/tmp}/liveupdate-mutants.XXXXXX")
trap 'rm -f "$listed"' EXIT HUP INT TERM
cargo mutants --list --no-config >"$listed"
python3 -I - "$root" "$listed" <<'PYTHON'
import pathlib, re, sys

root, listed = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
config = (root / ".cargo/mutants.toml").read_text()
block = re.search(r"^exclude_re = \[\n(.*?)^\]", config, re.M | re.S)
patterns = re.findall(r"^\s*'(.*)',\s*$", block.group(1), re.M) if block else []
mutants = listed.read_text().splitlines()

# The code that each exclusion's reason is about, by the function its mutant is in. A new exclusion
# adds its function here.
expected = {
    "Driver::drain_events": "Ok(EventBatch::default())",
    "Driver::starts": "Ok(Vec::new())",
    "valid_app_file": "|| value.contains('\\\\')",
}

failures = []
if not patterns:
    failures.append(".cargo/mutants.toml has no exclude_re")
for pattern in patterns:
    matches = [mutant for mutant in mutants if re.search(pattern, mutant)]
    if len(matches) != 1:
        failures.append(f"{pattern} matches {len(matches)} mutants, not one")
        continue
    place = re.match(r"(src/[^:]+):(\d+):(\d+): ", matches[0])
    function = next((name for name in expected if name in matches[0]), None)
    if place is None or function is None:
        failures.append(f"{matches[0]}: no place, or a function that this check does not know")
        continue
    path, line, column = place.group(1), int(place.group(2)), int(place.group(3))
    source = (root / path).read_text().splitlines()[line - 1]
    if expected[function] not in source or source[column - 1:].strip() != source.strip():
        failures.append(f"{matches[0]}: line {line} is {source.strip()!r}, not the code its exclusion is about")
for failure in failures:
    print(f"FAIL {failure}")
if failures:
    sys.exit(1)
print(f"mutant exclusions: each of the {len(patterns)} is at the code it leaves out")
PYTHON
