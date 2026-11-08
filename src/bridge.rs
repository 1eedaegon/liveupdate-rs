use crate::{Capabilities, Driver, DriverRecord, Error, Observation, Result};
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
