# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| Public beta (current) | Yes |
| Earlier alpha builds | No |

## Reporting a vulnerability

Please report security issues **privately** before public disclosure:

1. Open a [GitHub Security Advisory](https://github.com/ummalife/khandaq/security/advisories/new) on this repository, **or**
2. Contact the Khandaq team via https://khandaq.org/

Include:

- Affected platform (desktop / Android / iOS)
- Steps to reproduce
- Impact assessment (confidentiality, integrity, availability)
- Proof-of-concept if available

We aim to acknowledge reports within **72 hours**.

## Scope

**In scope:**

- Khandaq client source in this repository
- Documented public endpoints (`bootstrap.khandaq.org`, `push.khandaq.org`)
- Build and release integrity (checksums, supply chain)

**Out of scope:**

- Vulnerabilities in upstream Tox protocol design (report to [c-toxcore](https://github.com/TokTok/c-toxcore))
- Social engineering, physical device access
- Denial-of-service against public Tox bootstrap nodes not operated by Khandaq

## What we do not have access to

- Your Tox message plaintext (E2E encrypted)
- Your Tox private keys (stored locally, encrypted with your password)
- Message content on `push.khandaq.org` (wake notifications only)

## Recommended verification

- Compare release `SHA256SUMS.txt` with downloaded binaries
- Build clients from source and compare behaviour
- Review `config/khandaq_push.json` and bootstrap registry for network transparency
