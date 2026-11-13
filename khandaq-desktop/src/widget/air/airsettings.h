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

#include <QWidget>

class AirLocal;
class IGroupSettings;
class QLabel;
class QVBoxLayout;

/**
 * Settings > Privacy > Blocked: how many keys this device blocked ("None" when none), and every
 * blocked key in groups of four, oldest first, each with Unblock. Unblocking only lets requests
 * from the key show again; the contact itself comes back with a new request.
 */
class AirBlockedKeys : public QWidget
{
    Q_OBJECT
public:
    explicit AirBlockedKeys(AirLocal& airLocal, QWidget* parent = nullptr);
    ~AirBlockedKeys() override;

private:
    void rebuild();
    void retranslate();
    void restyle();

    AirLocal& airLocal;
    QLabel* title = nullptr;
    QLabel* value = nullptr;
    QLabel* sectionNote = nullptr;
    QWidget* listBox = nullptr;
    QVBoxLayout* list = nullptr;
};

/**
 * Settings > Privacy > Hidden in groups: the members whose messages and voice this device stops
 * showing in every group (Hide their messages in a group dossier), by key as Blocked lists them,
 * each with Show their messages. The list is the one the group code filters by (blackList), kept
 * in the profile settings.
 */
class AirHiddenMembers : public QWidget
{
    Q_OBJECT
public:
    explicit AirHiddenMembers(IGroupSettings& settings, QWidget* parent = nullptr);
    ~AirHiddenMembers() override;

private:
    void rebuild();
    void retranslate();
    void restyle();

    IGroupSettings& settings;
    QLabel* title = nullptr;
    QLabel* value = nullptr;
    QLabel* sectionNote = nullptr;
    QWidget* listBox = nullptr;
    QVBoxLayout* list = nullptr;
};
