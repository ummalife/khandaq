#!/usr/bin/env bash
# Khandaq bootstrap node installer (tox-bootstrapd)
# Usage: sudo ./install.sh
set -euo pipefail

BOOT_USER="${BOOT_USER:-toxbootstrap}"
BOOT_DIR="${BOOT_DIR:-/var/lib/khandaq-bootstrap}"
CONFIG_DIR="${CONFIG_DIR:-/etc/khandaq-bootstrap}"
UDP_PORT="${UDP_PORT:-33445}"
TCP_PORT="${TCP_PORT:-33445}"
# Pin c-toxcore release tag (security audit #14)
TOXCORE_TAG="${TOXCORE_TAG:-v0.2.20}"

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates build-essential git cmake ninja-build libconfig-dev libsodium-dev python3

if ! id "$BOOT_USER" &>/dev/null; then
  useradd --system --home "$BOOT_DIR" --shell /usr/sbin/nologin "$BOOT_USER"
fi

mkdir -p "$BOOT_DIR" "$CONFIG_DIR"
chown -R "$BOOT_USER:$BOOT_USER" "$BOOT_DIR"

if [[ ! -x /usr/local/bin/tox-bootstrapd ]]; then
  TMP=$(mktemp -d)
  git clone --depth 1 --branch "$TOXCORE_TAG" https://github.com/toktok/c-toxcore.git "$TMP/c-toxcore"
  cmake -S "$TMP/c-toxcore" -B "$TMP/build" -GNinja \
    -DCMAKE_BUILD_TYPE=Release -DBOOTSTRAP_DAEMON=ON -DDHT_BOOTSTRAP=OFF \
    -DBUILD_TOXAV=OFF -DUNITTEST=OFF -DBUILD_MISC_TESTS=OFF
  cmake --build "$TMP/build" --target tox-bootstrapd -j"$(nproc)"
  install -m 755 "$TMP/build/other/bootstrap_daemon/tox-bootstrapd" /usr/local/bin/tox-bootstrapd
  rm -rf "$TMP"
fi

if [[ ! -f "$CONFIG_DIR/bootstrap.conf" ]]; then
  cat >"$CONFIG_DIR/bootstrap.conf" <<EOF
port = $UDP_PORT
keys_file_path = "$BOOT_DIR/keys"
pid_file_path = "$BOOT_DIR/tox-bootstrapd.pid"
enable_ipv6 = true
enable_ipv4_fallback = true
enable_lan_discovery = false
enable_tcp_relay = true
tcp_relay_ports = [$TCP_PORT, 443, 3389]
enable_motd = true
motd = "Khandaq bootstrap — https://khandaq.org"
bootstrap_nodes = (
  { address = "tox1.mf-net.eu"; port = 33445; public_key = "B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506" },
  { address = "tox2.mf-net.eu"; port = 33445; public_key = "70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F" }
)
EOF
  chown -R "$BOOT_USER:$BOOT_USER" "$CONFIG_DIR"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
install -m 644 "$SCRIPT_DIR/khandaq-bootstrap.service" /etc/systemd/system/khandaq-bootstrap.service
systemctl daemon-reload

echo "Installed. Next steps:"
echo "  1. sudo -u $BOOT_USER mkdir -p $BOOT_DIR && sudo -u $BOOT_USER /usr/local/bin/tox-bootstrapd --config $CONFIG_DIR/bootstrap.conf --log-backend stdout --foreground"
echo "     (first run creates keys in $BOOT_DIR/keys — stop with Ctrl+C once keys exist)"
echo "  2. sudo systemctl enable --now khandaq-bootstrap"
echo "  3. Update config/khandaq_bootstrap_nodes.json with public_key from $BOOT_DIR/keys"
