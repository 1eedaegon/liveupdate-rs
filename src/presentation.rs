use crate::{Error, MAX_TIMESTAMP, Result, Template, Timer};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

/// Maximum number of actions on one notification.
pub const MAX_ACTIONS: usize = 3;
/// Maximum number of metrics in Android's expanded metric style.
pub const MAX_METRICS: usize = 3;
/// Maximum number of progress segments or points.
pub const MAX_PROGRESS_ELEMENTS: usize = 32;

const MAX_BIG_TEXT: usize = 4_000;
const MAX_ACTION_ID: usize = 64;
const MAX_ACTION_TITLE: usize = 64;
const MAX_ICON_PATH: usize = 240;
const MAX_RESOURCE_NAME: usize = 120;
const MAX_METRIC_LABEL: usize = 10;
const MAX_METRIC_UNIT: usize = 8;
const MAX_METRIC_TEXT: usize = 64;
const MAX_CHIP_TEXT: usize = 7;
const MAX_SAMSUNG_PRIMARY: usize = 120;
const MAX_SAMSUNG_SECONDARY: usize = 240;
const MAX_URL: usize = 2_048;
const MAX_SUB_TEXT: usize = 64;
/// Schemes a link may not have: none of them names a screen of the application.
const REFUSED_SCHEMES: [&str; 6] = [
    "intent",
    "content",
    "file",
    "javascript",
    "data",
    "android-app",
];

/// Android semantic color intent used by API 37 system styles.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SemanticStyle {
    /// No semantic color intent.
    Unspecified,
    /// Informational content.
    Info,
    /// A safe or successful state.
    Safe,
    /// A state that needs caution.
    Caution,
    /// A dangerous or failed state.
    Danger,
}

/// An icon the Android adapter may resolve without external storage access.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    content = "value",
    rename_all = "camelCase",
    deny_unknown_fields
)]
pub enum IconSource {
    /// A drawable or mipmap resource name in the host application.
    Resource(String),
    /// A relative path below the host application's private files directory.
    AppFile(String),
}

impl IconSource {
    /// Checks that the resource name or app-private relative path is safe and bounded.
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Resource(name) if valid_resource_name(name) => Ok(()),
            Self::AppFile(path) if valid_app_file(path) => Ok(()),
            _ => Err(Error::invalid()),
        }
    }
}

/// One action button delivered back as an [`crate::ActivityEvent`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NotificationAction {
    /// Stable caller-owned action identifier.
    pub id: String,
    /// System-rendered action label.
    pub title: String,
    /// Optional action icon.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<IconSource>,
    /// Whether Android should authenticate before sending the action.
    #[serde(default, skip_serializing_if = "is_false")]
    pub authentication_required: bool,
    /// URL the action opens in the application, instead of queuing an event.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub open_url: Option<String>,
}

impl NotificationAction {
    /// An action that queues an event, without an icon and without authentication.
    #[must_use]
    pub fn new(id: impl Into<String>, title: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            title: title.into(),
            icon: None,
            authentication_required: false,
            open_url: None,
        }
    }

    fn validate(&self) -> Result<()> {
        if !valid_identifier(&self.id, MAX_ACTION_ID)
            || !crate::model::valid_text(&self.title, MAX_ACTION_TITLE)
            || !valid_optional_url(self.open_url.as_deref())
        {
            return Err(Error::invalid());
        }
        validate_optional_icon(self.icon.as_ref())
    }
}

/// Optional BigTextStyle details.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BigTextStyle {
    /// Expanded notification text.
    pub text: String,
    /// Optional summary below the expanded text.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
}

impl BigTextStyle {
    fn validate(&self) -> Result<()> {
        if !crate::model::valid_text(&self.text, MAX_BIG_TEXT)
            || !valid_optional_text(self.summary.as_deref(), 240)
        {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// A colored part of a rich progress track.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProgressSegment {
    /// Positive relative length.
    pub length: u32,
    /// Stable identity across updates.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<u32>,
    /// Optional ARGB color.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub color: Option<u32>,
    /// Optional API 37 semantic styling.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub semantic_style: Option<SemanticStyle>,
}

/// A colored marker on a rich progress track.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProgressPoint {
    /// Positive position in segment units.
    pub position: u32,
    /// Stable identity across updates.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<u32>,
    /// Optional ARGB color.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub color: Option<u32>,
    /// Optional API 37 semantic styling.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub semantic_style: Option<SemanticStyle>,
}

/// Android's rich progress system style.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProgressStyle {
    /// Current progress in the same units as the segments.
    pub progress: u32,
    /// Whether progress is not currently known.
    #[serde(default, skip_serializing_if = "is_false")]
    pub indeterminate: bool,
    /// Whether Android styles completed and future elements differently.
    #[serde(default, skip_serializing_if = "is_false")]
    pub styled_by_progress: bool,
    /// Background track segments.
    pub segments: Vec<ProgressSegment>,
    /// Markers placed on the track.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub points: Vec<ProgressPoint>,
    /// Optional icon at the beginning of the track.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_icon: Option<IconSource>,
    /// Optional icon at the end of the track.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_icon: Option<IconSource>,
    /// Optional icon tracking current progress.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tracker_icon: Option<IconSource>,
}

impl ProgressStyle {
    /// Validates bounds and relationships required by Android ProgressStyle.
    pub fn validate(&self) -> Result<()> {
        let maximum = self.validate_segments()?;
        self.validate_points(maximum)?;
        if self.progress as u64 > maximum {
            return Err(Error::invalid());
        }
        for icon in [
            self.start_icon.as_ref(),
            self.end_icon.as_ref(),
            self.tracker_icon.as_ref(),
        ] {
            validate_optional_icon(icon)?;
        }
        Ok(())
    }

    fn validate_segments(&self) -> Result<u64> {
        if self.segments.is_empty() || self.segments.len() > MAX_PROGRESS_ELEMENTS {
            return Err(Error::invalid());
        }
        let mut ids = BTreeSet::new();
        let mut maximum = 0_u64;
        for segment in &self.segments {
            if segment.length == 0 || !insert_optional_id(&mut ids, segment.id) {
                return Err(Error::invalid());
            }
            maximum = maximum
                .checked_add(u64::from(segment.length))
                .ok_or_else(Error::invalid)?;
        }
        if maximum > i32::MAX as u64 {
            return Err(Error::invalid());
        }
        Ok(maximum)
    }

    fn validate_points(&self, maximum: u64) -> Result<()> {
        if self.points.len() > MAX_PROGRESS_ELEMENTS {
            return Err(Error::invalid());
        }
        let mut ids = BTreeSet::new();
        let mut previous = 0;
        for point in &self.points {
            let position = u64::from(point.position);
            if position == 0
                || position > maximum
                || position <= previous
                || !insert_optional_id(&mut ids, point.id)
            {
                return Err(Error::invalid());
            }
            previous = position;
        }
        Ok(())
    }
}

/// Chronometer details for a timer presentation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TimerStyle {
    /// Timer interval.
    pub timer: Timer,
    /// Count down to the end; otherwise count up from the start.
    pub counts_down: bool,
}

impl TimerStyle {
    fn validate(&self) -> Result<()> {
        if self.timer.ends_at > MAX_TIMESTAMP || self.timer.ends_at <= self.timer.started_at {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// A value Android can render in MetricStyle on API 37 and later.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    content = "value",
    rename_all = "camelCase",
    deny_unknown_fields
)]
pub enum MetricValue {
    /// Integer value with an optional short unit.
    Integer {
        /// Value shown by Android.
        value: i32,
        /// Optional unit.
        #[serde(skip_serializing_if = "Option::is_none")]
        unit: Option<String>,
    },
    /// Decimal value with an optional short unit.
    Decimal {
        /// Finite value representable as an Android float.
        value: f64,
        /// Optional unit.
        #[serde(skip_serializing_if = "Option::is_none")]
        unit: Option<String>,
    },
    /// Fixed short text.
    Text(String),
    /// Running stopwatch from a Unix timestamp in milliseconds.
    Stopwatch {
        /// Stopwatch start. Versions up to 0.3.0 wrote `started_at`, which the adapter refused:
        /// it is still read.
        #[serde(rename = "startedAt", alias = "started_at")]
        started_at: u64,
    },
    /// Running countdown to a Unix timestamp in milliseconds.
    Countdown {
        /// Countdown end. Versions up to 0.3.0 wrote `ends_at`, which the adapter refused: it is
        /// still read.
        #[serde(rename = "endsAt", alias = "ends_at")]
        ends_at: u64,
    },
}

impl MetricValue {
    fn validate(&self) -> Result<()> {
        match self {
            Self::Integer { unit, .. } => validate_unit(unit.as_deref()),
            Self::Decimal { value, unit } => {
                if !value.is_finite() || !(*value as f32).is_finite() {
                    return Err(Error::invalid());
                }
                validate_unit(unit.as_deref())
            }
            Self::Text(text) if crate::model::valid_text(text, MAX_METRIC_TEXT) => Ok(()),
            Self::Stopwatch { started_at } if *started_at <= MAX_TIMESTAMP => Ok(()),
            Self::Countdown { ends_at } if *ends_at <= MAX_TIMESTAMP => Ok(()),
            _ => Err(Error::invalid()),
        }
    }
}

/// One labeled metric.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Metric {
    /// Short metric label.
    pub label: String,
    /// Typed metric value.
    pub value: MetricValue,
    /// Optional semantic styling.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub semantic_style: Option<SemanticStyle>,
}

impl Metric {
    /// Validates the label and typed value.
    pub fn validate(&self) -> Result<()> {
        if !crate::model::valid_text(&self.label, MAX_METRIC_LABEL) {
            return Err(Error::invalid());
        }
        self.value.validate()
    }
}

/// Android's API 37 metric system style.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MetricStyle {
    /// One to three metrics.
    pub metrics: Vec<Metric>,
    /// Optional zero-based critical metric used by compact surfaces.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub critical_metric: Option<u8>,
}

impl MetricStyle {
    /// Validates count, values, and the optional critical index.
    pub fn validate(&self) -> Result<()> {
        if self.metrics.is_empty()
            || self.metrics.len() > MAX_METRICS
            || self
                .critical_metric
                .is_some_and(|index| usize::from(index) >= self.metrics.len())
        {
            return Err(Error::invalid());
        }
        self.metrics.iter().try_for_each(Metric::validate)
    }
}

/// One Android system presentation style.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    content = "value",
    rename_all = "camelCase",
    deny_unknown_fields
)]
pub enum PresentationStyle {
    /// Android's standard notification template.
    Standard,
    /// Expanded long text.
    BigText(BigTextStyle),
    /// Rich progress track.
    Progress(ProgressStyle),
    /// Up or down chronometer.
    Timer(TimerStyle),
    /// Up to three live metrics.
    Metric(MetricStyle),
}

impl PresentationStyle {
    fn validate(&self) -> Result<()> {
        match self {
            Self::Standard => Ok(()),
            Self::BigText(style) => style.validate(),
            Self::Progress(style) => style.validate(),
            Self::Timer(style) => style.validate(),
            Self::Metric(style) => style.validate(),
        }
    }

    pub(crate) fn template(&self) -> Template {
        match self {
            Self::Standard => Template::Standard,
            Self::BigText(_) => Template::BigText,
            Self::Progress(_) => Template::Progress,
            Self::Timer(_) => Template::Timer,
            Self::Metric(_) => Template::Metric,
        }
    }
}

/// Explicit Samsung Now Bar text mapping.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SamsungNowBar {
    /// Optional first line; title is used when absent.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary: Option<String>,
    /// Optional second line; status is used when absent.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub secondary: Option<String>,
}

impl SamsungNowBar {
    fn validate(&self) -> Result<()> {
        if !valid_optional_text(self.primary.as_deref(), MAX_SAMSUNG_PRIMARY)
            || !valid_optional_text(self.secondary.as_deref(), MAX_SAMSUNG_SECONDARY)
        {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// What a secure lock screen shows instead of a private activity's content.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PublicVersion {
    /// Title, limited to 120 Unicode scalar values.
    pub title: String,
    /// Status, limited to 240 Unicode scalar values.
    pub status: String,
}

impl PublicVersion {
    fn validate(&self) -> Result<()> {
        if !crate::model::valid_text(&self.title, 120)
            || !crate::model::valid_text(&self.status, 240)
        {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// Resolved Android presentation attached to a schema-v2 snapshot.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AndroidPresentation {
    /// System style and its typed data.
    pub style: PresentationStyle,
    /// Optional text for the promoted status chip.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub short_critical_text: Option<String>,
    /// Optional large icon.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub large_icon: Option<IconSource>,
    /// Up to three action buttons.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub actions: Vec<NotificationAction>,
    /// Optional closed Samsung Now Bar mapping.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub samsung_now_bar: Option<SamsungNowBar>,
    /// Optional clock in the notification's header, beside any style but
    /// [`PresentationStyle::Timer`], which is that clock alone.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chronometer: Option<TimerStyle>,
    /// URL the content tap opens in the application; the launch activity when absent.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub open_url: Option<String>,
    /// Whether this revision may alert again; every other one updates silently.
    #[serde(default, skip_serializing_if = "is_false")]
    pub alert: bool,
    /// What a secure lock screen shows of a private activity.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub public_version: Option<PublicVersion>,
    /// Small icon, a resource of the application; the AAR's own icon when absent.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub small_icon: Option<IconSource>,
    /// Optional ARGB accent color of the icon and the header.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub accent_color: Option<u32>,
    /// Optional third text of the header.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub_text: Option<String>,
}

impl AndroidPresentation {
    /// A presentation of `style` with none of the optional members, to build on:
    /// `AndroidPresentation { large_icon: Some(icon), ..AndroidPresentation::new(style) }`.
    #[must_use]
    pub fn new(style: PresentationStyle) -> Self {
        Self {
            style,
            short_critical_text: None,
            large_icon: None,
            actions: Vec::new(),
            samsung_now_bar: None,
            chronometer: None,
            open_url: None,
            alert: false,
            public_version: None,
            small_icon: None,
            accent_color: None,
            sub_text: None,
        }
    }

    /// Validates the complete resolved Android presentation.
    pub fn validate(&self) -> Result<()> {
        self.style.validate()?;
        self.validate_chronometer()?;
        self.validate_actions()?;
        if !valid_optional_text(self.short_critical_text.as_deref(), MAX_CHIP_TEXT) {
            return Err(Error::invalid());
        }
        validate_optional_icon(self.large_icon.as_ref())?;
        if let Some(samsung) = &self.samsung_now_bar {
            samsung.validate()?;
        }
        self.validate_extensions()
    }

    fn validate_extensions(&self) -> Result<()> {
        if !valid_optional_url(self.open_url.as_deref())
            || !valid_optional_text(self.sub_text.as_deref(), MAX_SUB_TEXT)
            // A small icon is drawn from a resource: Android tints its shape.
            || matches!(self.small_icon, Some(IconSource::AppFile(_)))
        {
            return Err(Error::invalid());
        }
        validate_optional_icon(self.small_icon.as_ref())?;
        self.public_version
            .as_ref()
            .map_or(Ok(()), PublicVersion::validate)
    }

    fn validate_chronometer(&self) -> Result<()> {
        let Some(chronometer) = &self.chronometer else {
            return Ok(());
        };
        if matches!(self.style, PresentationStyle::Timer(_)) {
            return Err(Error::invalid());
        }
        chronometer.validate()
    }

    fn validate_actions(&self) -> Result<()> {
        if self.actions.len() > MAX_ACTIONS {
            return Err(Error::invalid());
        }
        let mut ids = BTreeSet::new();
        for action in &self.actions {
            action.validate()?;
            if !ids.insert(action.id.as_str()) {
                return Err(Error::invalid());
            }
        }
        Ok(())
    }

    pub(crate) fn template(&self) -> Template {
        self.style.template()
    }
}

fn validate_optional_icon(icon: Option<&IconSource>) -> Result<()> {
    icon.map_or(Ok(()), IconSource::validate)
}

fn validate_unit(unit: Option<&str>) -> Result<()> {
    if valid_optional_text(unit, MAX_METRIC_UNIT) {
        Ok(())
    } else {
        Err(Error::invalid())
    }
}

fn valid_optional_text(value: Option<&str>, maximum: usize) -> bool {
    value.is_none_or(|text| crate::model::valid_text(text, maximum))
}

fn valid_optional_url(value: Option<&str>) -> bool {
    value.is_none_or(valid_url)
}

/// An absolute URL of printable ASCII with a lowercase scheme that is not refused.
fn valid_url(value: &str) -> bool {
    let Some((scheme, rest)) = value.split_once(':') else {
        return false;
    };
    let mut characters = scheme.chars();
    let lowercase_scheme = characters
        .next()
        .is_some_and(|first| first.is_ascii_lowercase())
        && characters.all(|character| {
            character.is_ascii_lowercase()
                || character.is_ascii_digit()
                || matches!(character, '+' | '.' | '-')
        });
    lowercase_scheme
        && !rest.is_empty()
        && value.len() <= MAX_URL
        && value.bytes().all(|byte| (0x21..=0x7e).contains(&byte))
        && !REFUSED_SCHEMES.contains(&scheme)
}

fn valid_identifier(value: &str, maximum: usize) -> bool {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return false;
    };
    value.chars().count() <= maximum
        && first.is_ascii_alphanumeric()
        && characters.all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | ':' | '-')
        })
}

fn valid_resource_name(value: &str) -> bool {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return false;
    };
    value.len() <= MAX_RESOURCE_NAME
        && first.is_ascii_lowercase()
        && characters.all(|character| {
            character.is_ascii_lowercase() || character.is_ascii_digit() || character == '_'
        })
}

fn valid_app_file(value: &str) -> bool {
    if value.is_empty()
        || value.len() > MAX_ICON_PATH
        || value.starts_with('/')
        || value.contains('\\')
    {
        return false;
    }
    value.split('/').all(|segment| {
        !segment.is_empty()
            && !matches!(segment, "." | "..")
            && segment.chars().all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
            })
    })
}

fn insert_optional_id(ids: &mut BTreeSet<u32>, id: Option<u32>) -> bool {
    id.is_none_or(|value| value <= i32::MAX as u32 && ids.insert(value))
}

fn is_false(value: &bool) -> bool {
    !value
}
