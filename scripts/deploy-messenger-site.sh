#!/usr/bin/env bash
# Deprecated wrapper — the messenger site was folded into the main site.
# Everything now ships via deploy-site.sh -> https://khandaq.org/ (and /messenger/
# 301-redirects there). Kept only so old muscle-memory / CI references keep working.
exec "$(cd "$(dirname "$0")" && pwd)/deploy-site.sh" "$@"
