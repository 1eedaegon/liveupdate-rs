# Testing

## Primary Android test device

The release gate uses the newest public stable Android API and the latest Pixel phone hardware
profile available to the installed SDK. As of this repository bootstrap, that is Android 17 /
API 37. The test harness must boot the emulator, wait for `sys.boot_completed=1`, and record
`ro.build.version.sdk`, `ro.product.model` and `ro.build.fingerprint` before running tests.

The instrumentation suite will cover capabilities, start, update, end, action, dismissal,
notification denial and process recreation. A run that merely builds without booting the emulator
does not satisfy the Android gate.

## OEM devices

Galaxy S23 is not the primary gate because OS/API and One UI rollout determine promoted Live Update
support; the model name alone does not. S23 can remain in the physical-device compatibility matrix
for fallback behavior. Before a stable release, use a current physical device whose firmware
exposes promoted ongoing notifications to verify OEM system-UI behavior.

## Compatibility matrix

| Target | Purpose | Required |
| --- | --- | --- |
| Latest Pixel phone profile, Android 17 / API 37 | Full promoted flow | Every Android change |
| Older Android emulator | Regular ongoing fallback | Backend changes |
| Promotion disabled | Permission/settings handling | Backend changes |
| Physical promoted-capable device | OEM presentation | Stable release |
| Galaxy S23 when available | Unsupported/fallback behavior | Compatibility pass |
