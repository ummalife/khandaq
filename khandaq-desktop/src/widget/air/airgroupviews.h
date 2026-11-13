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

#include <QAbstractButton>
#include <QBasicTimer>
#include <QDeadlineTimer>
#include <QHash>
#include <QSet>
#include <QVector>
#include <QWidget>

class AirTextField;
class AirTile;
class QLabel;
class QPainter;
class QVBoxLayout;

/**
 * "Get it now" under the members line of a group (E13): the tiles of the members online besides
 * you, as many as fit, then "+N". Someone who comes online fades in, or appears at once with less
 * motion. A click, Enter or Space opens the group dossier.
 */
class AirGetNowBand : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirGetNowBand(QWidget* parent = nullptr);
    ~AirGetNowBand() override;

    /** The members who get a message now, in the order of the strip (Air::reachableNow). */
    void setMembers(const QVector<Air::GroupMember>& reachable);
    /** The voice of the member with this key is coming now: their tile is ringed for a moment. */
    void setSpeaking(const QString& key);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void timerEvent(QTimerEvent* event) override;

private:
    /** How long a ring stays after the last packet of someone's voice. */
    static constexpr int speakingMs = 500;

    void retranslate();
    void restyle();
    void describe();
    /** Places the words and as many tiles as the width takes, then "+N" for the others. */
    void fit();
    void paintRings(QPainter& painter);

    QVector<Air::GroupMember> members;
    QSet<QString> shownKeys;
    QHash<QString, QDeadlineTimer> speaking;
    QBasicTimer speakingTimer;
    QLabel* label = nullptr;
    QVector<AirTile*> tiles;
    QLabel* more = nullptr;
    QWidget* rings = nullptr;
};

/**
 * A member in the group dossier: the tile with a dot while they are online, the name ("(you)" on
 * your own row), the role and whether they are online. Any row but yours opens the member's
 * actions.
 */
class AirMemberRow : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirMemberRow(const Air::GroupMember& member, QWidget* parent = nullptr);

    const Air::GroupMember& member() const;
    /** The ground the row stands on now: the dot's ring takes it. */
    QColor ground() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    QString nameText() const;
    QString statusText() const;

    Air::GroupMember shown;
    AirTile* tile = nullptr;
    QWidget* dot = nullptr;
    bool hovered = false;
};

/** kq-radio: the round mark, the choice in words and, under them, what it means. */
class AirRadioRow : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirRadioRow(QWidget* parent = nullptr);

    void setNote(const QString& note);

    bool hasHeightForWidth() const override;
    int heightForWidth(int width) const override;
    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    /** Where the title and the note stand for a row of this width. */
    void measure(int width, QRect* title, QRect* note) const;

    QString note;
    bool hovered = false;
};

/**
 * A contact on an invitation list: the square mark that fills when chosen, the tile, the name and,
 * while the contact is offline, that they can be invited once online. Such a row cannot be chosen.
 */
class AirInviteRow : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirInviteRow(const Air::InviteCandidate& candidate, QWidget* parent = nullptr);

    const Air::InviteCandidate& candidate() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    Air::InviteCandidate shown;
    AirTile* tile = nullptr;
    bool hovered = false;
};

/**
 * The contacts to invite, those who can be invited now first, with a search from eight contacts
 * on. The choice goes by public key: a friend number is only a slot of this session. Used by New
 * group and by Invite in the group dossier.
 */
class AirInviteList : public QWidget
{
    Q_OBJECT
public:
    explicit AirInviteList(QWidget* parent = nullptr);
    ~AirInviteList() override;

    /** The list follows the contacts; a choice stays while its contact can still be invited. */
    void setCandidates(const QVector<Air::InviteCandidate>& candidates);
    /** The chosen contacts who can be invited now, in the order of the list. */
    QVector<Air::InviteCandidate> chosen() const;
    /** Chooses a contact by key, as "To new group" in a contact's menu does. */
    void choose(const QString& publicKey);
    void clearChoice();

signals:
    void choiceChanged(int count);

private:
    void retranslate();
    void restyle();
    void rebuild();

    QVector<Air::InviteCandidate> candidates;
    QSet<QString> chosenKeys;
    QString query;
    AirTextField* search = nullptr;
    QLabel* empty = nullptr;
    QWidget* rowsHolder = nullptr;
    QVBoxLayout* rows = nullptr;
};
