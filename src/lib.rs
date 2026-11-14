//! Rust-first contracts and lifecycle primitives for Android Live Updates.
//!
//! Android framework integration is provided by a narrow managed adapter because
//! notification framework types and components are Java/Kotlin APIs. The stable
//! contract and lifecycle remain in Rust.
//!
//! Platform selection belongs to callers that combine this Android core with
//! other backends, so the core does not expose a backend discriminator.
//!
//! ```compile_fail
//! use liveupdate::Backend;
//! ```

mod bridge;
mod client;
mod contract;
mod driver;
mod error;
mod event;
mod model;
mod presentation;

#[cfg(all(feature = "android", target_os = "android"))]
mod android;

#[cfg(all(feature = "android", target_os = "android"))]
pub use android::{AndroidBridge, AndroidDriver};
pub use bridge::{Bridge, JsonDriver};
pub use client::LiveUpdate;
pub use contract::{Capabilities, Fallback, PresentationCapabilities, PromotionState, Visibility};
pub use driver::{Driver, DriverRecord, Observation, StartOutcome, StartReport};
pub use error::{Error, ErrorCode, Result};
pub use event::{ActivityEvent, EventBatch, EventKind, MAX_EVENTS};
pub use model::{
    ActivityRecord, ActivityState, EndPolicy, MAX_SAFE_INTEGER, MAX_TIMESTAMP, NextCheck,
    Placement, Snapshot, StartRequest, Template, Timer,
};
pub use presentation::{
    AndroidPresentation, BigTextStyle, IconSource, MAX_ACTIONS, MAX_METRICS, MAX_PROGRESS_ELEMENTS,
    Metric, MetricStyle, MetricValue, NotificationAction, PresentationStyle, ProgressPoint,
    ProgressSegment, ProgressStyle, PublicVersion, SamsungNowBar, SemanticStyle, TimerStyle,
};

// LU-DOCS-1
#[cfg(doctest)]
#[doc = include_str!("../README.md")]
pub struct ReadmeDoctests;
