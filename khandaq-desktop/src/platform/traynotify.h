/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

#pragma once

#include "src/model/notificationdata.h"

#include <QObject>
#include <QPointer>
#include <QSystemTrayIcon>

class INotificationSettings;

/**
 * System notifications without snorenotify (redesign E22): the builds for macOS and Linux are made
 * with DESKTOP_NOTIFICATIONS off, and Qt's tray icon shows the notification there instead, in the
 * Notification Centre of macOS and through the notification service of the Linux desktop. The tray
 * icon carries them, so they show while it is on screen; the settings say so when it is not.
 */
class TrayNotify : public QObject
{
    Q_OBJECT
public:
    explicit TrayNotify(const INotificationSettings& settings, QObject* parent = nullptr);

    /** The icon that carries the notifications; null until the system tray is there. */
    void setTrayIcon(QSystemTrayIcon* icon);
    /** Whether a notification can show now: the tray icon is on screen and takes messages. */
    bool canShow() const;
    /** Whether a notification is shown: notifications and system notifications are on. */
    static bool wanted(bool notify, bool desktopNotify, bool canShow);
    /**
     * Whether this desktop can carry a notification on a tray icon at all: it has a system tray
     * that takes messages. The walk of a test build runs without a screen, so without a tray;
     * KHANDAQ_SHOT_TRAY makes it answer as a desktop with one.
     */
    static bool systemCarries();

#ifdef KHANDAQ_SCREENSHOT_HOOK
    /** The notifications the walk would have seen, in the order they came. */
    static QVector<NotificationData>& shown();
#endif

public slots:
    void notifyMessage(const NotificationData& notificationData);

signals:
    /** The person opened or dismissed the notifications: the counts they carried start over. */
    void notificationClosed();
    /** The person clicked a notification: the window comes forward. */
    void activated();

private:
    const INotificationSettings& settings;
    QPointer<QSystemTrayIcon> icon;
};
