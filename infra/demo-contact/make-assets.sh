#!/usr/bin/env bash
# Regenerate the demo contact's media from the repository's own brand files (macOS only: it uses
# `say` for the two voice clips and `sips` for the images). The output is committed, so a deploy
# never depends on a Mac; run this again only to change what the welcome pack contains.
#
#   avatar.png               the app icon, sent as the contact's avatar
#   photo.jpg                the store promo banner, the first file in the welcome pack
#   voice_welcome.file.m4a   a short spoken voice message (".file.m4a" marks a voice note)
#   Khandaq-demo.pdf         a one-page "what you can try" document
#   greeting.pcm             16 kHz mono s16le, played to callers before the echo starts
#   nodes.txt                the iOS client's bootstrap list, so the bot meets clients where they are
#
# Needs: say, sips, ffmpeg, python3 with Pillow.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/assets"
VOICE="${VOICE:-Samantha}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in say sips ffmpeg python3; do
  command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done
mkdir -p "$OUT"

sips -z 256 256 "$ROOT/khandaq-ios/Antidote/Images.xcassets/AppIcon.appiconset/AppIcon-1024.png" \
  --out "$OUT/avatar.png" >/dev/null
sips -s format jpeg -s formatOptions 82 "$ROOT/brand/store/promo-banner-1200x628.png" \
  --out "$OUT/photo.jpg" >/dev/null

say -v "$VOICE" -o "$TMP/voice.aiff" \
  "Hi! This is a voice message from the Khandaq demo contact. Record one yourself, and I will send it right back to you."
ffmpeg -loglevel error -y -i "$TMP/voice.aiff" -ac 1 -ar 44100 -c:a aac -b:a 48k \
  "$OUT/voice_welcome.file.m4a"

say -v "$VOICE" -o "$TMP/greeting.aiff" \
  "Hello! You are connected to the Khandaq demo contact. This call is end to end encrypted and peer to peer. Now say something, and you will hear your own voice played back."
ffmpeg -loglevel error -y -i "$TMP/greeting.aiff" -ac 1 -ar 16000 -f s16le "$OUT/greeting.pcm"

python3 - "$OUT/Khandaq-demo.pdf" <<'PY'
import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 1240, 1754  # A4 at 150 dpi
page = Image.new("RGB", (W, H), (247, 250, 248))
draw = ImageDraw.Draw(page)

def font(size, bold=False):
    for name in (("Arial Bold.ttf" if bold else "Arial.ttf"), "Helvetica.ttc", "DejaVuSans.ttf"):
        for base in ("/System/Library/Fonts/Supplemental/", "/System/Library/Fonts/", "/Library/Fonts/", ""):
            try:
                return ImageFont.truetype(base + name, size)
            except OSError:
                continue
    return ImageFont.load_default()

draw.rectangle([0, 0, W, 260], fill=(8, 48, 42))
draw.text((90, 80), "Khandaq", font=font(84, True), fill=(104, 199, 147))
draw.text((90, 185), "A document sent by the demo contact", font=font(36), fill=(220, 236, 228))

y = 350
draw.text((90, y), "This PDF travelled to you peer to peer,", font=font(40, True), fill=(20, 30, 26))
y += 60
draw.text((90, y), "end-to-end encrypted, with no server in between.", font=font(40, True), fill=(20, 30, 26))
y += 130
lines = [
    "Things to try with the demo contact:",
    "",
    "•  Send a message: it replies and reacts.",
    "•  Send a photo, video, file or voice message:",
    "     it sends the same file back.",
    "•  Call it, audio or video: it answers and plays",
    "     your own voice and camera back to you.",
    "•  Type “call me”: it calls you in ten seconds.",
    "•  Type “edit” or “delete” to see message editing",
    "     and delete-for-both.",
    "•  Accept the group invitation to try group chat.",
    "•  Type “help” for all commands.",
]
for index, line in enumerate(lines):
    draw.text((90, y), line, font=font(34, index == 0), fill=(40, 52, 46))
    y += 58
draw.text((90, H - 140), "khandaq.org", font=font(32, True), fill=(8, 48, 42))
page.save(sys.argv[1], "PDF", resolution=150)
PY

python3 - "$ROOT/khandaq-ios/local_pod_repo/objcTox/Classes/Public/Manager/nodes.json" "$OUT/nodes.txt" <<'PY'
import json, sys
nodes = json.load(open(sys.argv[1]))["nodes"]
with open(sys.argv[2], "w") as out:
    out.write("# host port public_key [tcp_ports] -- generated from the iOS client's nodes.json by make-assets.sh\n")
    for n in nodes:
        tcp = ",".join(str(p) for p in n.get("tcp_ports") or [])
        for host in (n.get("ipv4"), n.get("ipv6")):
            if host and host != "-":
                out.write(f"{host} {n['port']} {n['public_key']} {tcp}".rstrip() + "\n")
PY

ls -la "$OUT"
