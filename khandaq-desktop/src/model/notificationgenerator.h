/*
    Copyright © 2020 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#pragma once


#include "notificationdata.h"
#include "friend.h"
#include "group.h"

#include "src/persistence/inotificationsettings.h"
#include "src/persistence/profile.h"

#include <QObject>
#include <QHash>

class NotificationGenerator : public QObject
{
    Q_OBJECT

public:
    NotificationGenerator(
        INotificationSettings const& notificationSettings_,
        // Optional profile input to lookup avatars. Avatar lookup is not
        // currently mockable so we allow profile to be nullptr for unit
        // testing
        Profile* profile_);
    virtual ~NotificationGenerator();
    NotificationGenerator(const NotificationGenerator&) = delete;
    NotificationGenerator& operator=(const NotificationGenerator&) = delete;
    NotificationGenerator(NotificationGenerator&&) = delete;
    NotificationGenerator& operator=(NotificationGenerator&&) = delete;

    NotificationData friendMessageNotification(const Friend* f, const QString& message);
    NotificationData groupMessageNotification(const Group* g, const ToxPk& sender, const QString& message);
    NotificationData fileTransferNotification(const Friend* f, const QString& filename, size_t fileSize);
    NotificationData groupInvitationNotification(const Friend* from,
                                                 const QString& groupName = QString());
    NotificationData friendRequestNotification(const ToxPk& sender, const QString& message);
    /**
     * KHANDAQ (0.2.64): f reacted to a message or a file of ours, as reaction says it
     * (AirStrings::reactionWords): the contact's name over it, only "New reaction" with message
     * text off. A reaction is no message: the counts in the titles of messages leave it out.
     */
    NotificationData friendReactionNotification(const Friend* f, const QString& reaction);
    /** KHANDAQ (0.2.64): the same in a group: its name over who reacted and the words. */
    NotificationData groupReactionNotification(const Group* g, const ToxPk& sender,
                                               const QString& reaction);

public slots:
    void onNotificationActivated();

private:
    INotificationSettings const& notificationSettings;
    Profile* profile;
    QHash<const Friend*, size_t> friendNotifications;
    QHash<const Group*, size_t> groupNotifications;
};
