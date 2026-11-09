//! What the README and the other docs must say, and what they may no longer say. The README's Rust
//! code itself compiles as a doctest (`ReadmeDoctests` in src/lib.rs).

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
fn the_readme_shows_its_rust_code_whole() {
    let blocks = rust_blocks(&read("README.md"));
    assert!(!blocks.is_empty());
    for block in blocks {
        for line in block.lines() {
            let trimmed = line.trim_start();
            assert!(
                !(trimmed == "#" || trimmed.starts_with("# ")),
                "the README hides a doctest line: {line}"
            );
        }
        assert!(block.contains("LiveUpdate::android()"), "{block}");
    }
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
}

// LU-DOCS-2
#[test]
fn the_docs_state_what_an_app_must_know() {
    let readme = prose("README.md");
    for phrase in [
        "request the runtime notification permission on Android 13",
        "one retained client per process",
        "`EndPolicy::After` returns `UNSUPPORTED_FEATURE`",
        "Visibility is reported as `Unknown`",
        "Galaxy S23 behavior on its current firmware (Android 17, One UI 9) is unverified",
        "Before that, `LiveUpdate::android()` returns `NATIVE_ERROR`",
        "docs/spec.md",
    ] {
        assert!(readme.contains(phrase), "README.md does not say {phrase:?}");
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
