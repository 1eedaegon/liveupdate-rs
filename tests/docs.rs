//! What the README and the other docs must say, and what they may no longer say. The README's Rust
//! code is examples/delivery.rs, which compiles for the host and for Android, and also compiles as a
//! doctest (`ReadmeDoctests` in src/lib.rs).

use std::path::Path;

fn read(path: &str) -> String {
    std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join(path)).unwrap()
}

/// The text with its line breaks read as single spaces, as a reader sees a wrapped paragraph.
fn prose(path: &str) -> String {
    read(path).split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The Rust code blocks of a Markdown file: fenced as `rust` or without a language, as rustdoc reads them.
fn rust_blocks(markdown: &str) -> Vec<String> {
    let mut blocks = Vec::new();
    // `None` outside a block; `Some(None)` inside a block of another language.
    let mut current: Option<Option<String>> = None;
    for line in markdown.lines() {
        let fence = line.trim_start();
        match current.take() {
            None if fence.starts_with("```") => {
                let language = fence.trim_start_matches('`');
                current =
                    Some((language.is_empty() || language.starts_with("rust")).then(String::new));
            }
            None => {}
            Some(block) if fence.starts_with("```") => blocks.extend(block),
            Some(Some(mut block)) => {
                block.push_str(line);
                block.push('\n');
                current = Some(Some(block));
            }
            Some(None) => current = Some(None),
        }
    }
    blocks
}

// LU-DOCS-1
#[test]
fn the_readme_shows_the_compiled_example_whole() {
    // examples/delivery.rs compiles for Android (`cargo ndk check --all-targets --features
    // android`) and for the host (`cargo clippy --all-targets --all-features`).
    let example = read("examples/delivery.rs");
    assert!(example.contains("LiveUpdate::android()"));
    assert_eq!(
        rust_blocks(&read("README.md")),
        [example],
        "the README's Rust code is not examples/delivery.rs verbatim"
    );
}

// LU-DOCS-1
#[test]
fn the_primary_example_uses_the_rich_schema() {
    let example = read("examples/delivery.rs");
    for required in [
        "schema_version: 2",
        "AndroidPresentation",
        "PresentationStyle::Progress",
        "ProgressSegment",
        "NotificationAction",
    ] {
        assert!(
            example.contains(required),
            "the primary example does not demonstrate {required}"
        );
    }
    assert!(
        !example.contains("schema_version: 1"),
        "the primary example still uses the legacy schema"
    );
}

// LU-DOCS-2
#[test]
fn the_readme_installs_this_version_and_its_aar() {
    let readme = prose("README.md");
    let version = env!("CARGO_PKG_VERSION");
    for phrase in [
        format!(r#"liveupdate = {{ version = "{version}", features = ["android"] }}"#),
        format!("liveupdate-android-{version}.aar"),
        "https://github.com/1eedaegon/liveupdate-rs/releases".into(),
    ] {
        assert!(
            readme.contains(&phrase),
            "README.md does not say {phrase:?}"
        );
    }
    assert!(prose("CHANGELOG.md").contains(&format!("## {version}")));
    // The AAR's classes need the Kotlin standard library it was compiled against, or a newer one.
    let gradle = read("build.gradle.kts");
    let kotlin = gradle
        .lines()
        .find_map(|line| {
            line.trim()
                .strip_prefix(r#"id("org.jetbrains.kotlin.android") version ""#)
        })
        .and_then(|rest| rest.split('"').next())
        .expect("build.gradle.kts names the Kotlin plugin's version");
    let stdlib = format!(r#"implementation("org.jetbrains.kotlin:kotlin-stdlib:{kotlin}")"#);
    assert!(
        readme.contains(&stdlib),
        "README.md does not say {stdlib:?}"
    );
}

// LU-DOCS-2
#[test]
fn the_docs_state_what_an_app_must_know() {
    let readme = prose("README.md");
    for phrase in [
        "request the runtime notification permission on Android 13",
        "one retained client per process",
        "`EndPolicy::After` keeps the last content until its time",
        "cannot be more than 14 days old",
        "Visibility is reported as `Unknown`",
        "A Galaxy S23 (`SM-S911N`) on Android 17 / One UI 9 has passed",
        "`LiveUpdate::events()`",
        "Before that, `LiveUpdate::android()` returns `NATIVE_ERROR`, as long as the build unwinds on panic",
        "docs/spec.md",
    ] {
        assert!(readme.contains(phrase), "README.md does not say {phrase:?}");
    }
    // Each feature, with the name a caller writes.
    for feature in [
        "`PresentationStyle::Standard`",
        "`PresentationStyle::BigText(",
        "`PresentationStyle::Progress(",
        "`PresentationStyle::Timer(",
        "`PresentationStyle::Metric(",
        "`short_critical_text:",
        "`large_icon:",
        "`NotificationAction::new(",
        "`open_url:",
        "`chronometer:",
        "`alert: true`",
        "`public_version:",
        "`small_icon:",
        "`accent_color:",
        "`sub_text:",
        "`samsung_now_bar:",
        "`placement: Placement::Foreground`",
        "`end(id, EndPolicy::After { at })`",
        "LiveUpdateEvents.opened(this, intent)",
        "LiveUpdateForeground.attach(this, ",
        "`state_changes()`",
        "`next_check()`",
        "`NextCheck::Soon`",
        "`AndroidPresentation::new(style)`",
    ] {
        assert!(
            readme.contains(feature),
            "README.md does not show {feature:?}"
        );
    }
    let testing = prose("docs/testing.md");
    for phrase in ["tests/android/run.sh", "EXPECTED_SDK", "spec-coverage.sh"] {
        assert!(
            testing.contains(phrase),
            "docs/testing.md does not say {phrase:?}"
        );
    }
}

// LU-DOCS-2
#[test]
fn no_doc_says_what_is_no_longer_true() {
    for file in ["README.md", "docs/testing.md"] {
        let text = prose(file);
        for stale in [
            "docs/implementation",
            "while the first crate release is being prepared",
            "Galaxy S23 is currently a fallback compatibility target",
            "git = \"https://github.com/1eedaegon/liveupdate-rs\"",
            "connectedDebugAndroidTest",
        ] {
            assert!(!text.contains(stale), "{file} still says {stale:?}");
        }
    }
    assert!(
        !Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("docs/implementation")
            .exists()
    );
}
