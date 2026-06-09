#!/usr/bin/env python3
"""Configure Uptime Kuma via Socket.IO API (monitors + optional Telegram/SMTP)."""
from __future__ import annotations

import os
import secrets
import subprocess
import sys


def ensure_pkg() -> None:
    try:
        import uptime_kuma_api  # noqa: F401
    except ImportError:
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "-q", "uptime-kuma-api"],
        )


def main() -> int:
    ensure_pkg()
    from uptime_kuma_api import MonitorType, NotificationType, UptimeKumaApi

    url = os.environ.get("KUMA_URL", "http://127.0.0.1:3001")
    user = os.environ.get("KUMA_ADMIN_USER", "khandaq-ops")
    password = os.environ.get("KUMA_ADMIN_PASS") or secrets.token_urlsafe(16)
    creds_file = os.environ.get("KUMA_CREDS_FILE", "/opt/khandaq-monitoring/kuma-admin.env")

    notify_ids: list[int] = []

    with UptimeKumaApi(url, timeout=30) as api:
        if api.need_setup():
            print(f"==> First-time setup: user={user}")
            api.setup(user, password)
        else:
            print("==> Login")
            api.login(user, password)

        tg_token = os.environ.get("KUMA_TELEGRAM_TOKEN", "")
        tg_chat = os.environ.get("KUMA_TELEGRAM_CHAT", "")
        if tg_token and tg_chat:
            print("==> Telegram notification")
            n = api.add_notification(
                type=NotificationType.TELEGRAM,
                name="Khandaq Telegram",
                isDefault=True,
                telegramBotToken=tg_token,
                telegramChatID=tg_chat,
                applyExisting=True,
            )
            if n.get("id"):
                notify_ids.append(n["id"])
        elif os.environ.get("KUMA_SMTP_HOST") and os.environ.get("KUMA_SMTP_TO"):
            print("==> SMTP notification")
            n = api.add_notification(
                type=NotificationType.SMTP,
                name="Khandaq Email",
                isDefault=True,
                smtpHost=os.environ["KUMA_SMTP_HOST"],
                smtpPort=int(os.environ.get("KUMA_SMTP_PORT", "587")),
                smtpUsername=os.environ.get("KUMA_SMTP_USER", ""),
                smtpPassword=os.environ.get("KUMA_SMTP_PASS", ""),
                smtpTo=os.environ["KUMA_SMTP_TO"],
                applyExisting=True,
            )
            if n.get("id"):
                notify_ids.append(n["id"])
        else:
            print("==> No alert channel (set KUMA_TELEGRAM_TOKEN + KUMA_TELEGRAM_CHAT)")

        monitors = [
            ("bootstrap1 TCP", MonitorType.PORT, {"hostname": "bootstrap1.khandaq.org", "port": 33445}),
            ("bootstrap2 TCP", MonitorType.PORT, {"hostname": "bootstrap2.khandaq.org", "port": 33445}),
            ("bootstrap3 TCP", MonitorType.PORT, {"hostname": "bootstrap3.khandaq.org", "port": 33445}),
            ("bootstrap1 ping", MonitorType.PING, {"hostname": "bootstrap1.khandaq.org"}),
            ("bootstrap2 ping", MonitorType.PING, {"hostname": "bootstrap2.khandaq.org"}),
            ("bootstrap3 ping", MonitorType.PING, {"hostname": "bootstrap3.khandaq.org"}),
            ("registry HTTP", MonitorType.HTTP, {
                "url": "https://bootstrap.khandaq.org/nodes.json",
                "accepted_statuscodes": ["200-299"],
                "interval": 300,
            }),
        ]

        print("==> Monitors")
        for name, mtype, extra in monitors:
            try:
                api.add_monitor(
                    type=mtype,
                    name=name,
                    interval=extra.pop("interval", 60),
                    retryInterval=60,
                    maxretries=3,
                    notificationIDList=notify_ids,
                    **extra,
                )
                print(f"  + {name}")
            except Exception as exc:
                print(f"  ! {name}: {exc}")

    os.makedirs(os.path.dirname(creds_file), exist_ok=True)
    with open(creds_file, "w", encoding="utf-8") as fh:
        fh.write(f"KUMA_URL={url}\nKUMA_ADMIN_USER={user}\nKUMA_ADMIN_PASS={password}\n")
    os.chmod(creds_file, 0o600)

    print(f"\nDone. Creds: {creds_file}")
    print("UI: ssh -L 3001:127.0.0.1:3001 Khandaq → http://localhost:3001")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
