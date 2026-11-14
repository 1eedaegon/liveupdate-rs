use crate::{
    Capabilities, Driver, DriverRecord, Error, EventBatch, Observation, Result, StartReport,
};
use serde::{Deserialize, Serialize, de::DeserializeOwned};

const MAX_BRIDGE_BYTES: usize = 1_048_576;

/// Bounded request/reply transport to a managed Android adapter.
///
/// Implementations should dispatch only the command supplied by this crate and
/// return the adapter's serialized response envelope. Lifecycle decisions stay
/// in [`crate::LiveUpdate`].
pub trait Bridge: Send + Sync {
    /// Invokes one adapter command with a JSON object payload.
    fn invoke(&self, command: &str, payload: &str) -> Result<String>;
}

/// [`Driver`] implementation over the stable JSON bridge protocol.
pub struct JsonDriver<B> {
    bridge: B,
}

impl<B> JsonDriver<B> {
    /// Wraps a request/reply bridge as a live update driver.
    #[must_use]
    pub fn new(bridge: B) -> Self {
        Self { bridge }
    }

    /// Returns the wrapped bridge.
    #[must_use]
    pub fn into_inner(self) -> B {
        self.bridge
    }
}

impl<B: Bridge> Driver for JsonDriver<B> {
    fn capabilities(&self) -> Result<Capabilities> {
        self.request("capabilities", &Empty {})
    }

    fn read(&self) -> Result<Vec<DriverRecord>> {
        self.request("read", &Empty {})
    }

    fn write(&self, records: &[DriverRecord]) -> Result<()> {
        self.request("write", &WriteRequest { records })
    }

    fn observe(&self) -> Result<Vec<Observation>> {
        self.request("observe", &Empty {})
    }

    fn post(&self, record: &DriverRecord) -> Result<()> {
        self.request("post", &PostRequest { record })
    }

    fn cancel(&self, id: &str) -> Result<()> {
        self.request("cancel", &CancelRequest { id })
    }

    fn drain_events(&self) -> Result<EventBatch> {
        let events: EventBatch = self.request("events", &Empty {})?;
        events.validate()?;
        Ok(events)
    }

    fn schedule(&self, record: &DriverRecord) -> Result<()> {
        self.request("schedule", &PostRequest { record })
    }

    fn starts(&self) -> Result<Vec<StartReport>> {
        self.request("starts", &Empty {})
    }
}

impl<B: Bridge> JsonDriver<B> {
    fn request<I: Serialize, O: DeserializeOwned>(
        &self,
        command: &'static str,
        input: &I,
    ) -> Result<O> {
        let payload = serde_json::to_string(input).map_err(|_| Error::native())?;
        if payload.len() > MAX_BRIDGE_BYTES {
            return Err(Error::native());
        }
        let response = self.bridge.invoke(command, &payload)?;
        if response.len() > MAX_BRIDGE_BYTES {
            return Err(Error::native());
        }
        match serde_json::from_str(&response).map_err(|_| Error::native())? {
            Response::Ok { data } => Ok(data),
            Response::Error { error } => Err(error),
        }
    }
}

#[derive(Serialize)]
#[serde(deny_unknown_fields)]
struct Empty {}

#[derive(Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WriteRequest<'record> {
    records: &'record [DriverRecord],
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PostRequest<'record> {
    record: &'record DriverRecord,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CancelRequest<'id> {
    id: &'id str,
}

#[derive(Deserialize)]
#[serde(tag = "status", rename_all = "camelCase", deny_unknown_fields)]
enum Response<T> {
    Ok { data: T },
    Error { error: Error },
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ErrorCode;
    use std::sync::Mutex;

    struct Reply {
        response: String,
        calls: Mutex<usize>,
    }

    impl Bridge for Reply {
        fn invoke(&self, _command: &str, _payload: &str) -> Result<String> {
            *self.calls.lock().unwrap() += 1;
            Ok(self.response.clone())
        }
    }

    fn driver(response: String) -> JsonDriver<Reply> {
        JsonDriver::new(Reply {
            response,
            calls: Mutex::new(0),
        })
    }

    /// An `ok` envelope padded with trailing whitespace to exactly `length` bytes.
    fn padded(length: usize) -> String {
        let envelope = r#"{"status":"ok","data":null}"#;
        format!("{envelope}{}", " ".repeat(length - envelope.len()))
    }

    // LU-BRIDGE-3
    #[test]
    fn a_reply_of_exactly_one_mebibyte_is_read_and_one_byte_more_is_not() {
        assert_eq!(MAX_BRIDGE_BYTES, 1_048_576);
        assert!(driver(padded(MAX_BRIDGE_BYTES)).cancel("id").is_ok());
        let error = driver(padded(MAX_BRIDGE_BYTES + 1))
            .cancel("id")
            .unwrap_err();
        assert_eq!(error.code, ErrorCode::NativeError);
    }

    // LU-BRIDGE-2
    #[test]
    fn a_request_over_one_mebibyte_never_reaches_the_bridge() {
        let driver = driver(padded(40));
        let id = "i".repeat(MAX_BRIDGE_BYTES);
        assert_eq!(driver.cancel(&id).unwrap_err(), Error::native());
        assert_eq!(*driver.bridge.calls.lock().unwrap(), 0);
        driver
            .cancel(&"i".repeat(MAX_BRIDGE_BYTES - r#"{"id":""}"#.len()))
            .unwrap();
        assert_eq!(*driver.into_inner().calls.lock().unwrap(), 1);
    }

    // LU-BRIDGE-1
    #[test]
    fn requests_serialize_as_the_adapter_expects() {
        assert_eq!(serde_json::to_string(&Empty {}).unwrap(), "{}");
        assert_eq!(
            serde_json::to_string(&CancelRequest { id: "a\"b" }).unwrap(),
            r#"{"id":"a\"b"}"#
        );
        assert_eq!(
            serde_json::to_string(&WriteRequest { records: &[] }).unwrap(),
            r#"{"records":[]}"#
        );
    }
}
