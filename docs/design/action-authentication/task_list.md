# Task list

- [x] Looked up: the API level of the setting, what AndroidX does below it, and what the system UI of
  Android 8 to 12 does with a tap on a locked device (PRD, tech_doc).
- [x] PRD, architecture, system design and technical design.
- [x] Spec row LU-V2-ACTION-3; the limit in "Not proven by a test".
- [x] Tests, seen failing: the Kotlin tests for the missing rule and then, against a rule that holds
  nothing back, on what they assert; the device test on a Galaxy Watch4 with Android 14, for the intent
  that did not say what its action requires.
- [x] The extra, the rule, the receiver; the tests pass, the device test on the watch.
- [x] README and the member's documentation.
- [x] Review. Taken from it: the stored receipt says what an action requires for an intent that does
  not, as one of an earlier version; receipts that cannot be read protect every action; the rule moved
  out of the public receiver into `ActionTap`, so the receiver keeps its one constructor; the documents
  say "dropped" and "taps again", and name the lock screen without a credential and the delay after an
  unlock.
- [x] Format, Clippy, host tests, the Kotlin unit tests and lint, spec coverage. The action tests on
  the watch pass. The device suite on API 37 is CI's: the emulator's image is not on this machine.
