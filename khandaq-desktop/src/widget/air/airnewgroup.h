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

#include "src/model/air/airgroup.h"
#include "src/widget/air/airpanels.h"

#include <functional>

class AirButton;
class AirInviteList;
class AirRadioRow;
class AirTextField;
class QLabel;

/**
 * New group (E15, desktop): one panel that drops from under the Air band over the list column, as
 * New contact does. The name (one line, up to 48 bytes, which cannot change later), who can join
 * (By invitation only, chosen at first, or Open to anyone with the group key), each with what it
 * means, the contacts online to invite, and Create group with the reason it waits. Joining a group
 * by its key is the other page of the same panel, behind a quiet link: creating and joining are
 * found in one place.
 */
class AirNewGroup : public AirDropPanel
{
    Q_OBJECT
public:
    /** What the panel asks about the profile; Widget answers. Keys come in upper case. */
    struct Lookup
    {
        std::function<QVector<Air::InviteCandidate>()> candidates;
        /** Whether the group with this key is in the list already. */
        std::function<bool(const QString& groupKey)> hasGroup;
    };

    AirNewGroup(Lookup lookup, QWidget* column, QWidget* parent);
    ~AirNewGroup() override;

    /** Opens on a fresh New group; a contact's key chooses them for the invitations. */
    void openCreate(const QString& inviteKey = QString());
    /** Opens on joining by key; a non-empty text goes into the key field as if pasted. */
    void openJoin(const QString& text = QString());
    /** The contacts or their presence changed: the invitation list follows while it is open. */
    void refreshCandidates();
    /** toxcore did not create the group: the form stays as it was, with the reason. */
    void createFailed();
    /** toxcore did not take the key: the page stays, with the reason. */
    void joinFailed();

signals:
    void createRequested(const QString& name, Air::GroupPrivacy privacy,
                         const QVector<Air::InviteCandidate>& invited);
    void joinRequested(const QString& groupKey);
    void openGroupRequested(const QString& groupKey);

private:
    enum class Page
    {
        Create,
        Join,
    };

    void showPage(Page page);
    void resetCreate();
    void resetJoin();
    void retranslate();
    void restyle();
    void refreshCreate();
    void refreshJoin();
    void create();
    void join();
    QString groupKey() const;
    void focusFirst();

    Lookup lookup;
    Page page = Page::Create;

    QWidget* createPage = nullptr;
    QLabel* nameLabel = nullptr;
    AirTextField* nameField = nullptr;
    QLabel* nameNote = nullptr;
    QLabel* privacyLabel = nullptr;
    AirRadioRow* inviteOnlyRow = nullptr;
    AirRadioRow* openRow = nullptr;
    QLabel* inviteLabel = nullptr;
    QLabel* inviteNote = nullptr;
    AirInviteList* inviteList = nullptr;
    QLabel* createError = nullptr;
    AirButton* createButton = nullptr;
    QLabel* createWhy = nullptr;
    AirButton* joinLink = nullptr;

    QWidget* joinPage = nullptr;
    QLabel* keyLabel = nullptr;
    AirTextField* keyField = nullptr;
    QLabel* keyNote = nullptr;
    QLabel* alreadyLabel = nullptr;
    AirButton* openButton = nullptr;
    QLabel* joinError = nullptr;
    AirButton* joinButton = nullptr;
    QLabel* joinWhy = nullptr;
    AirButton* createLink = nullptr;
};
