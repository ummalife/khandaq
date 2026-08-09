#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(dirname "$(realpath "$0")")"
source "${SCRIPT_DIR}/common.sh"

readonly X264_DIR="x264"

# KHANDAQ (audit 10d): "stable" is a moving branch, so the build used to compile whatever
# had last been pushed to it, unverified — the tarball downloads next to this script all
# check a known sha256. Pin the exact commit instead and fail the build if HEAD differs.
# To bump: git ls-remote https://code.videolan.org/videolan/x264.git stable
readonly X264_GIT_URL="https://code.videolan.org/videolan/x264.git"
readonly X264_GIT_REF="b35605ace3ddf7c1a5d67a2eb553f034aef41d55" # refs/heads/stable as of 2026-08-09

checkout_pinned() {
    # code.videolan.org serves arbitrary commits (allowAnySHA1InWant), so fetching the pin by
    # sha is the fast path. On a mirror that refuses it we have to DEEPEN the clone: a plain
    # "git fetch origin stable" keeps the existing shallow boundary, so an older pin would
    # still be missing and the checkout below would die with "unknown revision".
    # --unshallow errors out on an already-complete clone, hence the third arm.
    git fetch --depth 1 origin "${X264_GIT_REF}" 2>/dev/null ||
        git fetch --unshallow origin stable 2>/dev/null ||
        git fetch origin stable
    git checkout --detach "${X264_GIT_REF}"
}

if [ -d "${X264_DIR}/.git" ]; then
    pushd "${X264_DIR}" >/dev/null
    checkout_pinned
    popd >/dev/null
else
    rm -rf "${X264_DIR}"
    git clone --depth 1 --branch stable "${X264_GIT_URL}" "${X264_DIR}"
    pushd "${X264_DIR}" >/dev/null
    checkout_pinned
    popd >/dev/null
fi

ACTUAL=$(git -C "${X264_DIR}" rev-parse HEAD)
if [ "${ACTUAL}" != "${X264_GIT_REF}" ]; then
    echo "Error: x264 HEAD doesn't match the pinned commit."
    echo "Expected: ${X264_GIT_REF}"
    echo "Got: ${ACTUAL}"
    exit 1
fi
echo "x264 ${X264_GIT_REF} checked out"
