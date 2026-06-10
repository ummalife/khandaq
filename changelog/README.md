# Khandaq Changelog

Change history for https://khandaq.org/changelog — generated from git and release notes.

## Tagging commits

In the commit **subject** (first line):

```
[critical] Fix unlock crash after password on Android
[important] Add strict push URL whitelist
[medium] Limit desktop markdown parsing to 16k
[low] Update deploy script comments
```

Alternative in the commit **body**:

```
severity: critical
```

Allowed levels: `critical`, `important`, `medium`, `low`.

Commits **without a tag** are excluded from the daily log (see full history on GitHub).

## Release notes (major changes)

For security audits, version releases — file `changelog/releases/YYYY-MM-DD-vX.Y.Z.json`:

```json
{
  "version": "0.2.5",
  "date": "2026-06-10",
  "title": "Security audit remediation",
  "summary": "Short overview of the release.",
  "commits": ["48cf534", "d3b7c7d"],
  "changes": [
    {
      "severity": "critical",
      "title": "Short title",
      "description": "What was wrong and what was fixed.",
      "platforms": ["android", "ios"]
    }
  ]
}
```

## Generate

```bash
python3 scripts/generate-changelog.py
# → web/changelog.json
```

Runs automatically in `scripts/deploy-site.sh` before upload.

## Workflow

1. Fix → commit with `[severity]` in subject
2. Major release → add/update `changelog/releases/*.json`
3. `python3 scripts/generate-changelog.py`
4. `bash scripts/deploy-site.sh` (or push to master — CI keeps JSON in sync)
