# Contributing to Khandaq

Thank you for helping improve Khandaq Messenger.

## How to contribute

1. **Fork** https://github.com/ummalife/khandaq
2. Create a feature branch from `master`
3. Make focused changes with clear commit messages
4. Open a Pull Request describing **what** and **why**

## Code guidelines

- Preserve **Tox protocol compatibility** — do not break interoperability with other Tox clients unless explicitly discussed.
- Match existing style in each subproject (Qt/C++ for desktop, Swift for iOS, Java/Kotlin for Android).
- Keep changes minimal; avoid unrelated refactors in the same PR.
- Do **not** commit secrets, keystores, `.env` files, or production credentials.

## Building before you submit

See [docs/BUILDING.md](docs/BUILDING.md). At minimum, verify the component you changed compiles.

## Translations

iOS and desktop include multiple locales. New UI strings should update `en.lproj` / English sources first; other languages can follow in separate PRs.

## Documentation

Public documentation in this repository is **English only** (`README.md`, `docs/*.md`).

## Security issues

Do not open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).

## Licensing

By contributing, you agree that your contributions are licensed under the same license as the component you modify (GPL-3.0+ or MPL-2.0 as applicable).
