//! Rust-first contracts and lifecycle primitives for Android Live Updates.
//!
//! Android framework integration is provided by a narrow managed adapter because
//! notification framework types and components are Java/Kotlin APIs. The stable
//! contract and lifecycle remain in Rust.

mod contract;

pub use contract::{Capabilities, Fallback, PromotionState, Visibility};
