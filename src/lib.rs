//! Rust-first contracts and lifecycle primitives for Android Live Updates.
//!
//! Android framework integration is provided by a narrow managed adapter because
//! notification framework types and components are Java/Kotlin APIs. The stable
//! contract and lifecycle remain in Rust.

mod bridge;
mod client;
mod contract;
mod driver;
mod error;
mod model;

#[cfg(all(feature = "android", target_os = "android"))]
mod android;

#[cfg(all(feature = "android", target_os = "android"))]
pub use android::{AndroidBridge, AndroidDriver};
pub use bridge::{Bridge, JsonDriver};
pub use client::LiveUpdate;
pub use contract::{Capabilities, Fallback, PromotionState, Visibility};
pub use driver::{Driver, DriverRecord, Observation};
pub use error::{Error, ErrorCode, Result};
pub use model::{
    ActivityRecord, ActivityState, Backend, EndPolicy, MAX_SAFE_INTEGER, MAX_TIMESTAMP, Snapshot,
    StartRequest, Template, Timer,
};

// LU-DOCS-1
#[cfg(doctest)]
#[doc = include_str!("../README.md")]
pub struct ReadmeDoctests;
