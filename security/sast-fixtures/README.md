# SAST fixtures

KHANDAQ (re-review 2026-08-22, KQ-10). Deliberately vulnerable snippets, one per Semgrep rule, so
that `scripts/check-sast-fixtures.py` can prove each rule actually fires. A rule nobody has watched
fire is a rule that might match nothing — which is worse than no rule, because it reads as coverage.

**None of this is built or shipped.** The files live outside every source set: Gradle sees only
`app/src/**`, the iOS targets only `khandaq-ios/Antidote` and the pod, and the relay image copies
only `infra/push/relay`. They exist to be scanned and for no other purpose.
