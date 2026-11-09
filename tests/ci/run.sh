#!/bin/sh
# Checks .github/workflows/ci.yml (or the workflow file given as the first argument) and tests/ci/cache.sh:
#   - which changed paths start which job: every source the emulator reaches starts it, docs start only the
#     spec job, and the workflows and CI scripts start everything;
#   - the emulator record's inputs: the files it hashes are the ones the suite reads, and the cache keys
#     are exact (no restore-keys) and name the API, the image and the AVD script;
#   - the `if:` of the steps that look for, run, record and upload the emulator suite's pass, so a skipped
#     or failed suite never leaves a record;
#   - each job's own `if:`, and that no suite's failure is swallowed (`|| true`, `continue-on-error`);
#   - publish.yml publishes to crates.io only by hand, with `publish` and CRATES_IO_PUBLISH, after the guard
#     and the AAR, from the `crates-io` environment;
#   - no workflow runs on pull_request_target, only publish.yml's AAR job may write contents, and every
#     action is pinned to a commit SHA.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
workflow=${1:-$root/.github/workflows/ci.yml}
work=$(mktemp -d "${TMPDIR:-/tmp}/liveupdate-ci.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

# The record: none without the suite's summary, and one with the inputs and the summary otherwise.
(
  cd "$root"
  export RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/output" TOOLCHAIN=1.96.0 NDK=27.3.13750724 CARGO_NDK=4.1.2 \
    ANDROID_API=37.0 JAVA=21 GRADLE=8.14.4
  sh tests/ci/cache.sh keys 2>/dev/null
  sh tests/ci/cache.sh record "$work/missing.txt" >/dev/null
  printf 'tests 31\n' >"$work/summary.txt"
  sh tests/ci/cache.sh record "$work/summary.txt" >/dev/null
  sh tests/ci/cache.sh files >"$work/files"
)
exec python3 -I - "$root" "$workflow" "$work" <<'PYTHON'
import pathlib, re, sys

root, workflow, work = map(pathlib.Path, sys.argv[1:])
text = workflow.read_text()
failures = []

def fail(message):
    failures.append(message)

# The change filters.
patterns = dict(re.findall(r"^ *area (\w+) '([^']*)'$", text, re.M))
everything = re.search(r"if grep -qE '([^']*)' <<< \"\$files\"; then all ", text).group(1)

def starts(area, *paths):
    for path in paths:
        if not (re.search(patterns[area], path) or re.search(everything, path)):
            fail(f"{area} does not run for {path}")

def skips(area, *paths):
    for path in paths:
        if re.search(patterns[area], path) or re.search(everything, path):
            fail(f"{area} runs for {path}")

sources = ["src/android.rs", "src/client.rs", "src/client/tests.rs", "src/bridge.rs", "src/lib.rs",
           "Cargo.toml", "Cargo.lock", "rust-toolchain.toml"]
starts("android", *sources, "android/src/main/java/io/github/eedaegon/liveupdate/AndroidAdapter.kt",
       "android/src/main/AndroidManifest.xml", "android/consumer-rules.pro", "android/build.gradle.kts",
       "android/native-test/src/lib.rs", "android/native-test/Cargo.lock",
       "android/src/androidTest/java/io/github/eedaegon/liveupdate/Device.kt",
       "android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt",
       "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "tests/android/run.sh",
       "tests/android/avd.sh", "tests/android/aar.sh", "tests/ci/cache.sh", ".github/workflows/ci.yml")
starts("rust", *sources, "tests/lifecycle.rs", "tests/common/mod.rs", "tests/docs.rs", "tests/android_env.sh",
       "scripts/check-android-env.sh", ".github/workflows/ci.yml", "android/native-test/src/lib.rs",
       "android/native-test/Cargo.toml", "examples/delivery.rs", ".cargo/mutants.toml")
starts("android", "examples/delivery.rs")
starts("spec", "examples/delivery.rs")
starts("spec", "src/client.rs", "docs/spec.md", "docs/testing.md", "tools/spec-coverage.sh", "README.md",
       "CHANGELOG.md", "tests/ci/run.sh", "tests/lifecycle.rs",
       "android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt")
for area in ("rust", "android"):
    skips(area, "README.md", "CHANGELOG.md", "docs/spec.md", "docs/design/architecture.md", "tools/spec-coverage.sh",
          "LICENSE-MIT", ".github/FUNDING.yml")
skips("rust", "android/src/main/AndroidManifest.xml", "tests/android/run.sh")
skips("android", "tests/lifecycle.rs", "tests/docs.rs", "scripts/check-android-env.sh")

# The record's inputs: the files the emulator suite reads.
hashed = set((work / "files").read_text().split())
for path in ["src/android.rs", "src/client.rs", "Cargo.lock", "rust-toolchain.toml", "build.gradle.kts",
             "android/build.gradle.kts", "android/src/main/AndroidManifest.xml",
             "android/native-test/src/lib.rs", "android/native-test/Cargo.lock",
             "android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt",
             "tests/android/run.sh", "tests/android/avd.sh"]:
    if path not in hashed:
        fail(f"the emulator record does not hash {path}")
for path in hashed:
    if path.startswith(("docs/", "README", "CHANGELOG", "tools/", ".github/")):
        fail(f"the emulator record hashes {path}, which the suite does not read")
outputs = dict(line.split("=", 1) for line in (work / "output").read_text().splitlines())
if not re.fullmatch(r"android-sdk-v\d+-.*-platforms-android-37\.0-emulator", outputs.get("sdk", "")):
    fail(f"the SDK cache key is {outputs.get('sdk')}")
if not re.fullmatch(r"android-avd-v\d+-.*-android-37\.0-google_apis-(x86_64|arm64-v8a)-[0-9a-f]{40}", outputs.get("avd", "")):
    fail(f"the AVD cache key is {outputs.get('avd')}")
if not re.fullmatch(r"passed-emulator-[0-9a-f]{40}", outputs.get("emulator", "")):
    fail(f"the record name is {outputs.get('emulator')}")
if outputs.get("recorded") != "true" or "recorded=false" not in (work / "output").read_text():
    fail("cache.sh record must refuse a missing summary and accept a present one")
record = (work / "passed-emulator" / "passed.txt").read_text()
for line in ["suite emulator", "rust 1.96.0", "ndk 27.3.13750724", "java 21 temurin", "gradle 8.14.4",
             "android 37.0 google_apis", "tests 31"]:
    if line not in record:
        fail(f"the record lacks {line!r}")
if "restore-keys" in text:
    fail("a cache restores by prefix: every key must be exact")

# The steps of the emulator job.
job = text[text.index("\n  emulator:\n"):]
steps = {}
for block in re.split(r"\n      - ", job)[1:]:
    fields = {}
    # The first field sits on the step's own line, the others are indented under it.
    for line in ["        " + block.splitlines()[0], *block.splitlines()[1:]]:
        match = re.match(r"^        (name|id|if|uses|run): (.*)$", line)
        if match and match.group(1) not in fields:
            fields[match.group(1)] = match.group(2)
    steps[fields.get("id") or fields.get("name") or fields.get("uses", "")] = fields

def condition(step, expected):
    found = steps.get(step, {}).get("if")
    if found != expected:
        fail(f"the emulator step {step!r} runs if {found!r}, not {expected!r}")

condition("passed", "${{ !inputs.fresh && !inputs.retest }}")
condition("Emulator suite", "steps.passed.outputs.found != 'true'")
condition("record", "steps.passed.outputs.found != 'true'")
if "$RUNNER_TEMP/device-suite/passed.txt" not in steps.get("record", {}).get("run", ""):
    fail("the record step does not take the suite's summary")
uploads = [s for s in steps.values() if s.get("if") == "steps.record.outputs.recorded == 'true'"]
if len(uploads) != 1 or "upload-artifact" not in uploads[0].get("uses", ""):
    fail("exactly one upload must send the record, and only when it was written")
if "name: ${{ steps.keys.outputs.emulator }}" not in job or "if-no-files-found: error" not in job:
    fail("the record's artifact must be named by its key and fail without its file")
if re.search(r"continue-on-error", job):
    fail("an emulator step continues on error, so a failed suite could leave a record")
order = list(steps)
if order.index("passed") > order.index("Emulator suite") or order.index("Emulator suite") > order.index("record"):
    fail("the emulator job must look for a pass, run the suite, then record it")

# Each job's own condition, and no swallowed failures.
jobs = dict(re.findall(r"^  ([a-z-]+):\n(?:    (?!if:).*\n)*?    if: (.*)$", text, re.M))
expected_jobs = {
    "spec": "needs.changes.outputs.spec == 'true'",
    "rust": "needs.changes.outputs.rust == 'true'",
    "msrv": "needs.changes.outputs.rust == 'true'",
    "mutants": "needs.changes.outputs.rust == 'true' && github.event_name != 'push'",
    "android": "needs.changes.outputs.android == 'true'",
    "emulator": "needs.changes.outputs.android == 'true'",
}
for name, condition_ in expected_jobs.items():
    if jobs.get(name) != condition_:
        fail(f"the {name} job runs if {jobs.get(name)!r}, not {condition_!r}")
if "changes" in jobs:
    fail("the changes job must always run")
for swallow in ("|| true", "|| :", "continue-on-error", "set +e"):
    if swallow in text:
        fail(f"ci.yml contains {swallow!r}, which can hide a failed suite")

# The release: crates.io only by hand, with `publish` checked and the repository variable set, after
# the AAR, from its environment, and the OIDC token only for that job.
release = (root / ".github/workflows/publish.yml").read_text()
publish = release[release.index("\n  crates-io:\n"):]
if "    needs: [validate, aar]\n" not in publish or "    environment: crates-io\n" not in publish:
    fail("publish.yml's crates-io job must need validate and aar and run in the crates-io environment")
aar_job = release[release.index("\n  aar:\n"):release.index("\n  crates-io:\n")]
if release.count("contents: write") != 1 or "contents: write" not in aar_job:
    fail("only publish.yml's aar job may write contents")
for required in ('gh release download "$TAG"', 'cmp "$aar"', 'cmp "$aar.sha256"'):
    if required not in aar_job:
        fail(f"publish.yml cannot safely reuse an existing release asset: missing {required!r}")
if "--clobber" in aar_job:
    fail("publish.yml may not overwrite an existing release asset")
if "    if: github.event_name == 'workflow_dispatch' && inputs.publish && vars.CRATES_IO_PUBLISH == 'true'\n" not in publish:
    fail("publish.yml publishes to crates.io without a manual run, `publish` and CRATES_IO_PUBLISH")
if release.count("id-token: write") != 1 or "id-token: write" not in publish:
    fail("publish.yml grants the OIDC token to a job other than crates-io")
if "rust-lang/crates-io-auth-action@" not in publish or "cargo publish --locked" not in publish:
    fail("publish.yml does not publish through Trusted Publishing")
if "no successful CI run on main for" not in release or "git merge-base --is-ancestor" not in release:
    fail("publish.yml does not require main's CI for the tag's commit")

# Triggers, permissions and pinned actions.
if "contents: write" in text:
    fail("ci.yml grants contents: write")
if "pull_request_target" in text:
    fail("ci.yml runs on pull_request_target")
for path in (root / ".github/workflows").glob("*.yml"):
    if "pull_request_target" in path.read_text():
        fail(f"{path.name} runs on pull_request_target")
    for action in re.findall(r"uses: ([^\s#]+)", path.read_text()):
        if not re.fullmatch(r"[\w.-]+/[\w./-]+@[0-9a-f]{40}", action):
            fail(f"{path.name} uses {action}, which is not pinned to a commit")

if failures:
    print("\n".join("FAIL " + failure for failure in failures))
    sys.exit(f"{len(failures)} CI check(s) failed")
print("ci: the filters start the jobs they must, the record hashes what the suite reads, and only a full pass is recorded")
PYTHON
