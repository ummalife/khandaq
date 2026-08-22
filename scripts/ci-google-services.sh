#!/usr/bin/env bash
# Put a google-services.json in place for a CI build.
#
# google-services.json is gitignored (audit A21 — it used to hold a committed Firebase key), so it is
# absent on a fresh checkout and the google-services Gradle plugin refuses to configure without it.
# If the owner supplies the real config as a secret we use it and the build is SHIPPABLE; otherwise
# we write a placeholder with the correct package name so an unsigned REFERENCE build can proceed.
#
# KHANDAQ (re-audit 2026-08-22, S-01): extracted from release-provenance.yml because a second
# workflow now needs it. Two copies of a file-writing heredoc inside two YAML files is how the two
# drift — and the failure mode is a build that configures with the wrong package name, which looks
# like a Firebase problem rather than a copy-paste problem.
#
#   scripts/ci-google-services.sh            # writes app/google-services.json if absent
#   GOOGLE_SERVICES_JSON=... the real config, if available
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/google-services.json}"
PKG="${GOOGLE_SERVICES_PACKAGE:-com.khandaq.messenger}"

if [ -n "${GOOGLE_SERVICES_JSON:-}" ]; then
  printf '%s' "${GOOGLE_SERVICES_JSON}" > "$DEST"
  echo "google-services.json: из секрета (сборка пригодна к публикации)"
  exit 0
fi

if [ -f "$DEST" ]; then
  echo "google-services.json: уже на месте, не трогаю"
  exit 0
fi

cat > "$DEST" <<JSON
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "khandaq-messenger",
    "storage_bucket": "khandaq-messenger.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
        "android_client_info": { "package_name": "${PKG}" }
      },
      "oauth_client": [],
      "api_key": [ { "current_key": "PLACEHOLDER_NOT_A_REAL_KEY_reference_build_only" } ],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
JSON
echo "google-services.json: записана заглушка для пакета ${PKG} (сборка только справочная)"
