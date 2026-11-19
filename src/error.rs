use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};

/// Stable machine-readable failure categories returned by the library.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum ErrorCode {
    /// A request failed boundary validation.
    InvalidInput,
    /// A live activity with incompatible state already exists.
    Conflict,
    /// The requested activity no longer exists.
    NotFound,
    /// An update revision is older than the accepted revision.
    StaleRevision,
    /// Required Android notification permission or settings are disabled.
    PermissionDenied,
    /// The requested behavior is unavailable and no fallback was allowed.
    UnsupportedFeature,
    /// A bound on the live activities was exceeded: their number, the one foreground placement,
    /// or what their receipts take to store.
    SystemLimit,
    /// The platform adapter or its durable storage failed.
    NativeError,
}

/// A stable error code with a sanitized human-readable message. It is written with these two
/// members; what a later adapter adds to them is not read.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Error {
    /// Machine-readable failure category.
    pub code: ErrorCode,
    /// Sanitized message that never repeats notification content.
    pub message: String,
}

impl Error {
    /// Creates an error for a driver implementation or integration boundary.
    #[must_use]
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub(crate) fn invalid() -> Self {
        Self::new(ErrorCode::InvalidInput, "Invalid live update request.")
    }

    pub(crate) fn native() -> Self {
        Self::new(
            ErrorCode::NativeError,
            "The Android live update adapter failed.",
        )
    }
}

impl Display for Error {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{:?}: {}", self.code, self.message)
    }
}

impl std::error::Error for Error {}

/// Result type used by the live update API and its platform driver.
pub type Result<T> = std::result::Result<T, Error>;

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const CODES: [(ErrorCode, &str); 8] = [
        (ErrorCode::InvalidInput, "INVALID_INPUT"),
        (ErrorCode::Conflict, "CONFLICT"),
        (ErrorCode::NotFound, "NOT_FOUND"),
        (ErrorCode::StaleRevision, "STALE_REVISION"),
        (ErrorCode::PermissionDenied, "PERMISSION_DENIED"),
        (ErrorCode::UnsupportedFeature, "UNSUPPORTED_FEATURE"),
        (ErrorCode::SystemLimit, "SYSTEM_LIMIT"),
        (ErrorCode::NativeError, "NATIVE_ERROR"),
    ];

    // LU-ERR-1
    #[test]
    fn every_code_has_one_stable_name() {
        for (code, name) in CODES {
            assert_eq!(serde_json::to_value(code).unwrap(), json!(name));
            assert_eq!(
                serde_json::from_value::<ErrorCode>(json!(name)).unwrap(),
                code
            );
        }
        assert!(serde_json::from_value::<ErrorCode>(json!("TIMEOUT")).is_err());
        assert!(serde_json::from_value::<ErrorCode>(json!("invalid_input")).is_err());
    }

    // LU-ERR-1, LU-COMPAT-3
    #[test]
    fn errors_carry_a_code_and_a_message_and_nothing_else() {
        let error = Error::new(ErrorCode::Conflict, "Activity exists.");
        assert_eq!(
            serde_json::to_value(&error).unwrap(),
            json!({ "code": "CONFLICT", "message": "Activity exists." })
        );
        assert_eq!(error.to_string(), "Conflict: Activity exists.");
        // A later adapter may say more: it is read for the two members, which must be there.
        assert_eq!(
            serde_json::from_value::<Error>(
                json!({ "code": "CONFLICT", "message": "Activity exists.", "cause": "x" })
            )
            .unwrap(),
            error
        );
        assert!(serde_json::from_value::<Error>(json!({ "code": "CONFLICT" })).is_err());
        assert!(serde_json::from_value::<Error>(json!({ "message": "m" })).is_err());
        let boxed: Box<dyn std::error::Error> = Box::new(error);
        assert!(boxed.source().is_none());
    }

    // LU-ERR-2
    #[test]
    fn the_library_messages_are_fixed_sentences() {
        assert_eq!(
            Error::invalid(),
            Error::new(ErrorCode::InvalidInput, "Invalid live update request.")
        );
        assert_eq!(
            Error::native(),
            Error::new(
                ErrorCode::NativeError,
                "The Android live update adapter failed."
            )
        );
    }
}
