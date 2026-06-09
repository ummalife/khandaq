#!/usr/bin/env python3
"""Assign Khandaq TestFlight build to targeted testers only."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

BUNDLE_ID = "org.khandaq.messenger"
CONFIG_PATH = os.path.expanduser("~/.appstoreconnect/config")
POLL_INTERVAL_SEC = 30
POLL_TIMEOUT_SEC = 30 * 60
TARGETED_GROUP_NAME = "Khandaq Alpha"


def load_config() -> dict[str, str]:
    config: dict[str, str] = {}
    with open(CONFIG_PATH) as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            config[key.strip()] = value.strip()
    required = ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_PATH")
    missing = [key for key in required if not config.get(key)]
    if missing:
        raise SystemExit(f"Missing config keys: {', '.join(missing)}")
    return config


def make_token(config: dict[str, str]) -> str:
    with open(config["ASC_KEY_PATH"]) as handle:
        private_key = handle.read()
    return jwt.encode(
        {
            "iss": config["ASC_ISSUER_ID"],
            "exp": int(time.time()) + 1200,
            "aud": "appstoreconnect-v1",
        },
        private_key,
        algorithm="ES256",
        headers={"kid": config["ASC_KEY_ID"], "typ": "JWT"},
    )


def api(config: dict[str, str], method: str, path: str, body: dict | None = None) -> dict:
    url = f"https://api.appstoreconnect.apple.com{path}"
    payload = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=payload, method=method)
    request.add_header("Authorization", f"Bearer {make_token(config)}")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=60) as response:
        raw = response.read().decode()
        return json.loads(raw) if raw else {}


def resolve_app_id(config: dict[str, str]) -> str:
    encoded = urllib.parse.quote(BUNDLE_ID)
    response = api(config, "GET", f"/v1/apps?filter[bundleId]={encoded}&limit=1")
    apps = response.get("data", [])
    if not apps:
        raise SystemExit(
            f"App {BUNDLE_ID} not found in App Store Connect. "
            "Create it once in App Store Connect → Apps → + (bundle org.khandaq.messenger)."
        )
    app_id = apps[0]["id"]
    print(f"App: {apps[0]['attributes'].get('name')} ({app_id})")
    return app_id


def _build_version_number(build: dict) -> int:
    version = build["attributes"].get("version", "")
    return int(version) if str(version).isdigit() else 0


def find_build(config: dict[str, str], app_id: str, delivery_uuid: str | None) -> dict:
    if delivery_uuid:
        try:
            response = api(config, "GET", f"/v1/builds/{delivery_uuid}?include=buildBetaDetail")
            return response["data"]
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            print(f"Delivery UUID is not a build id ({delivery_uuid}), polling ASC")

    prior_response = api(
        config,
        "GET",
        f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=1",
    )
    prior_builds = prior_response.get("data", [])
    prior_id = prior_builds[0]["id"] if prior_builds else None
    prior_version = _build_version_number(prior_builds[0]) if prior_builds else 0

    deadline = time.time() + POLL_TIMEOUT_SEC
    while time.time() < deadline:
        response = api(
            config,
            "GET",
            f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=5",
        )
        builds = response.get("data", [])
        if not builds:
            print("Waiting for uploaded build to appear in ASC...")
            time.sleep(POLL_INTERVAL_SEC)
            continue

        for build in builds:
            attrs = build["attributes"]
            version = attrs.get("version")
            state = attrs.get("processingState")
            uploaded = attrs.get("uploadedDate")
            is_new_upload = delivery_uuid and _build_version_number(build) > prior_version
            if delivery_uuid and not is_new_upload:
                continue

            print(f"Build {version}: {state} uploaded {uploaded}")
            if state == "VALID":
                return build
            if state in {"FAILED", "INVALID"}:
                raise SystemExit(f"Build processing failed: {state}")

        time.sleep(POLL_INTERVAL_SEC)

    raise SystemExit("Timed out waiting for uploaded build to become VALID")


def wait_until_valid(config: dict[str, str], build_id: str) -> dict:
    deadline = time.time() + POLL_TIMEOUT_SEC
    while time.time() < deadline:
        build = api(config, "GET", f"/v1/builds/{build_id}?include=buildBetaDetail")
        attributes = build["data"]["attributes"]
        state = attributes.get("processingState")
        version = attributes.get("version")
        print(f"Build {version}: {state}")
        if state == "VALID":
            return build["data"]
        if state in {"FAILED", "INVALID"}:
            raise SystemExit(f"Build processing failed: {state}")
        time.sleep(POLL_INTERVAL_SEC)
    raise SystemExit("Timed out waiting for build processing")


def assign_to_groups(config: dict[str, str], app_id: str, build_id: str, group_ids: list[str] | None = None) -> None:
    groups = api(config, "GET", f"/v1/apps/{app_id}/betaGroups?limit=50").get("data", [])
    if not groups:
        raise SystemExit("No beta groups found")

    allowed_ids = set(group_ids) if group_ids else None
    for group in groups:
        group_id = group["id"]
        if allowed_ids is not None and group_id not in allowed_ids:
            continue
        group_name = group["attributes"]["name"]
        current = api(config, "GET", f"/v1/betaGroups/{group_id}/builds?limit=50")
        assigned_ids = {item["id"] for item in current.get("data", [])}
        if build_id in assigned_ids:
            print(f"Group {group_name}: already assigned")
            continue
        api(
            config,
            "POST",
            f"/v1/betaGroups/{group_id}/relationships/builds",
            {"data": [{"type": "builds", "id": build_id}]},
        )
        print(f"Group {group_name}: assigned")


def submit_external_beta_review(config: dict[str, str], build_id: str) -> None:
    detail_response = api(config, "GET", f"/v1/builds/{build_id}/buildBetaDetail")
    detail = detail_response.get("data", {})
    external_state = detail.get("attributes", {}).get("externalBuildState")
    if external_state == "IN_BETA_TESTING":
        print(f"external beta: already IN_BETA_TESTING")
        return
    if external_state != "READY_FOR_BETA_SUBMISSION":
        print(f"external beta: skip submit (state={external_state})")
        return
    try:
        api(
            config,
            "POST",
            "/v1/betaAppReviewSubmissions",
            {
                "data": {
                    "type": "betaAppReviewSubmissions",
                    "relationships": {
                        "build": {"data": {"type": "builds", "id": build_id}},
                    },
                }
            },
        )
        print("external beta: submitted for App Review")
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        if error.code == 409:
            print("external beta: review submission already exists")
            return
        raise SystemExit(f"Beta review submit failed: {body}") from error


def ensure_auto_notify(config: dict[str, str], build: dict) -> None:
    detail_data = build.get("relationships", {}).get("buildBetaDetail", {}).get("data")
    if not detail_data:
        build = api(config, "GET", f"/v1/builds/{build['id']}?include=buildBetaDetail")["data"]
        detail_data = build.get("relationships", {}).get("buildBetaDetail", {}).get("data")
    if not detail_data:
        print("Build beta detail unavailable, skipping autoNotify")
        return
    detail_id = detail_data["id"]
    detail_response = api(config, "GET", f"/v1/buildBetaDetails/{detail_id}")
    detail = detail_response.get("data")
    if not detail:
        return
    if detail["attributes"].get("autoNotifyEnabled"):
        print("autoNotify: already enabled")
        return
    api(
        config,
        "PATCH",
        f"/v1/buildBetaDetails/{detail_id}",
        {
            "data": {
                "type": "buildBetaDetails",
                "id": detail_id,
                "attributes": {"autoNotifyEnabled": True},
            }
        },
    )
    print("autoNotify: enabled")


def find_tester_in_group(config: dict[str, str], group_id: str, email: str) -> dict | None:
    response = api(config, "GET", f"/v1/betaGroups/{group_id}/betaTesters?limit=200")
    email_lower = email.lower()
    for tester in response.get("data", []):
        email_attr = tester["attributes"].get("email") or ""
        if email_attr.lower() == email_lower:
            return tester
    return None


def create_tester_in_group(config: dict[str, str], group_id: str, email: str) -> dict:
    local = email.split("@", 1)[0]
    print(f"Adding tester to group: {email}")
    response = api(
        config,
        "POST",
        "/v1/betaTesters",
        {
            "data": {
                "type": "betaTesters",
                "attributes": {
                    "email": email.lower(),
                    "firstName": "Khandaq",
                    "lastName": local[:20],
                },
                "relationships": {
                    "betaGroups": {"data": [{"type": "betaGroups", "id": group_id}]},
                },
            }
        },
    )
    return response["data"]


def send_tester_invitation(config: dict[str, str], app_id: str, tester_id: str, email: str) -> None:
    try:
        api(
            config,
            "POST",
            "/v1/betaTesterInvitations",
            {
                "data": {
                    "type": "betaTesterInvitations",
                    "relationships": {
                        "app": {"data": {"type": "apps", "id": app_id}},
                        "betaTester": {"data": {"type": "betaTesters", "id": tester_id}},
                    },
                }
            },
        )
        print(f"Invitation sent: {email}")
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        if "NO_INSTALLABLE_BUILDS" in body:
            print(f"Invitation queued for {email} (build not approved for external testing yet)")
            return
        if error.code == 409:
            print(f"Invitation skipped for {email} (already invited or accepted)")
            return
        raise


def find_or_create_targeted_group(config: dict[str, str], app_id: str) -> dict:
    groups = api(config, "GET", f"/v1/apps/{app_id}/betaGroups?limit=50").get("data", [])
    for group in groups:
        if group["attributes"].get("name") == TARGETED_GROUP_NAME:
            return group

    print(f"Creating beta group: {TARGETED_GROUP_NAME}")
    response = api(
        config,
        "POST",
        "/v1/betaGroups",
        {
            "data": {
                "type": "betaGroups",
                "attributes": {"name": TARGETED_GROUP_NAME},
                "relationships": {
                    "app": {"data": {"type": "apps", "id": app_id}},
                },
            }
        },
    )
    return response["data"]


def invite_all_pending_testers(config: dict[str, str], app_id: str) -> None:
    invited = 0
    skipped = 0
    seen_ids: set[str] = set()

    try:
        groups = api(config, "GET", f"/v1/apps/{app_id}/betaGroups?limit=50").get("data", [])
    except urllib.error.HTTPError as error:
        print(f"invitations: skipped listing groups ({error.code})")
        return

    for group in groups:
        group_name = group["attributes"].get("name", group["id"])
        try:
            response = api(config, "GET", f"/v1/betaGroups/{group['id']}/betaTesters?limit=200")
        except urllib.error.HTTPError as error:
            print(f"invitations: group {group_name} skipped ({error.code})")
            continue

        for tester in response.get("data", []):
            tester_id = tester["id"]
            if tester_id in seen_ids:
                continue
            seen_ids.add(tester_id)

            attrs = tester["attributes"]
            email = attrs.get("email") or ""
            state = attrs.get("state")
            if state == "ACCEPTED":
                skipped += 1
                continue
            if not email:
                continue
            send_tester_invitation(config, app_id, tester_id, email)
            invited += 1

    print(f"invitations: sent={invited} skipped_accepted={skipped}")


def distribute_to_testers(config: dict[str, str], app_id: str, build_id: str, emails: list[str]) -> str:
    group = find_or_create_targeted_group(config, app_id)
    group_id = group["id"]

    for email in emails:
        tester = find_tester_in_group(config, group_id, email)
        if tester is None:
            tester = create_tester_in_group(config, group_id, email)
        attrs = tester["attributes"]
        state = attrs.get("state")
        print(f"Tester {attrs.get('email')}: {state}")
        if state == "ACCEPTED":
            print(f"Invitation skipped for {email} (already accepted)")
        else:
            send_tester_invitation(config, app_id, tester["id"], email)

    assign_to_groups(config, app_id, build_id, group_ids=[group_id])
    return group["attributes"]["name"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Distribute Khandaq TestFlight build")
    parser.add_argument("delivery_uuid", nargs="?", help="Delivery UUID from altool upload")
    parser.add_argument("--testers", help="Comma-separated tester emails")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    delivery_uuid = args.delivery_uuid or os.environ.get("DELIVERY_UUID")
    tester_emails = [
        email.strip().lower()
        for email in (args.testers or os.environ.get("TESTERS", "")).split(",")
        if email.strip()
    ]
    config = load_config()
    app_id = resolve_app_id(config)

    build = find_build(config, app_id, delivery_uuid)
    build_id = build["id"]
    build_number = build["attributes"]["version"]
    print(f"Target build: {build_number} ({build_id})")

    if build["attributes"].get("processingState") != "VALID":
        build = wait_until_valid(config, build_id)

    if tester_emails:
        group_name = distribute_to_testers(config, app_id, build_id, tester_emails)
        ensure_auto_notify(config, build)
        print(f"DONE: build {build_number} sent to {', '.join(tester_emails)} via {group_name}")
        return

    assign_to_groups(config, app_id, build_id)
    submit_external_beta_review(config, build_id)
    ensure_auto_notify(config, build)
    invite_all_pending_testers(config, app_id)
    print(f"DONE: build {build_number} distributed to all TestFlight groups")
    print("Public link: https://testflight.apple.com/join/4ppS8ZN5")


if __name__ == "__main__":
    main()
