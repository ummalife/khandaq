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

#include "traynotify.h"

#include "src/persistence/inotificationsettings.h"

#include <QDebug>
#include <QIcon>

TrayNotify::TrayNotify(const INotificationSettings& settings_, QObject* parent)
    : QObject(parent)
    , settings{settings_}
{
}

void TrayNotify::setTrayIcon(QSystemTrayIcon* icon_)
{
    if (icon) {
        disconnect(icon, nullptr, this, nullptr);
    }
    icon = icon_;
    if (icon) {
        connect(icon, &QSystemTrayIcon::messageClicked, this, [this] {
            emit notificationClosed();
            emit activated();
        });
    }
}

bool TrayNotify::canShow() const
{
    return icon && icon->isVisible() && QSystemTrayIcon::supportsMessages();
}

bool TrayNotify::wanted(bool notify, bool desktopNotify, bool canShow)
{
    return notify && desktopNotify && canShow;
}

bool TrayNotify::systemCarries()
{
#ifdef KHANDAQ_SCREENSHOT_HOOK
    if (qEnvironmentVariableIsSet("KHANDAQ_SHOT_TRAY")) {
        return true;
    }
#endif
    return QSystemTrayIcon::isSystemTrayAvailable() && QSystemTrayIcon::supportsMessages();
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
QVector<NotificationData>& TrayNotify::shown()
{
    static QVector<NotificationData> notifications;
    return notifications;
}
#endif

void TrayNotify::notifyMessage(const NotificationData& notificationData)
{
#ifdef KHANDAQ_SCREENSHOT_HOOK
    // The walk keeps the notification instead of showing it: the system would put it up on the
    // screen of the computer the walk runs on.
    if (wanted(settings.getNotify(), settings.getDesktopNotify(), true)) {
        shown().append(notificationData);
        qInfo() << "Notification kept for the walk:" << notificationData.title << "|"
                << notificationData.message;
    }
#else
    if (!wanted(settings.getNotify(), settings.getDesktopNotify(), canShow())) {
        return;
    }
    // The sender's photo when they chose one, else the icon of the app.
    const QIcon picture = notificationData.pixmap.isNull()
                              ? QIcon(QStringLiteral(":/img/icons/khandaq.svg"))
                              : QIcon(notificationData.pixmap);
    icon->showMessage(notificationData.title, notificationData.message, picture);
#endif
}
