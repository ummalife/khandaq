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

#include "src/model/air/airconversation.h"
#include "src/widget/air/airtokens.h"

#include <QAbstractButton>
#include <QPixmap>
#include <QPointer>
#include <QWidget>

class QLabel;
class QVariantAnimation;

/**
 * Small painted pieces of the conversation screen (design/system/site/system.css): the tile of a
 * chat, the link mark, the day strip and the switch. Colours come from AirStyle at paint time, so a
 * theme change only repaints them.
 */

/** kq-tile: initials in the person's tone, their photo, a group's double outline or a bookmark. */
class AirTile : public QWidget
{
    Q_OBJECT
public:
    enum class Kind
    {
        Person,
        Group,
        /** Notes to yourself: a bookmark in ink. */
        Saved,
        /** A request from someone who is not a contact yet: a dashed outline round "add contact". */
        Request,
    };

    explicit AirTile(int side = AirTokens::Size::tileDesk, QWidget* parent = nullptr);

    void setPerson(const QString& title, int tone);
    void setKind(Kind kind);
    /** A null pixmap goes back to the initials. */
    void setPhoto(const QPixmap& photo);

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    int side;
    QString title;
    int tone = 0;
    Kind kind = Kind::Person;
    QPixmap photo;
};

/** kq-lm: 30 by 10 px, a line between two points, broken by a relay or dashed when offline. */
class AirLinkMark : public QWidget
{
    Q_OBJECT
public:
    explicit AirLinkMark(QWidget* parent = nullptr);

    void setState(Air::LinkState state);

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    Air::LinkState state = Air::LinkState::None;
};

/**
 * kq-cells: 24 hourly cells of the last day. The compact strip is 168 px wide with 12 px cells,
 * the big one of the dossier takes the whole width with 28 px cells. The current hour is outlined,
 * and filled green while the person is online.
 */
class AirDayStrip : public QWidget
{
    Q_OBJECT
public:
    explicit AirDayStrip(bool big, QWidget* parent = nullptr);

    void setStrip(const Air::DayStrip& strip, bool liveNow);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    bool big;
    Air::DayStrip strip;
    bool liveNow = false;
};

/**
 * The link as a picture (sg-path): you on one side, the person on the other, a straight green line
 * between when the link is direct, broken by a relay in the middle, dashed while there is none.
 */
class AirPathPicture : public QWidget
{
    Q_OBJECT
public:
    explicit AirPathPicture(QWidget* parent = nullptr);

    /** initials stand in the far end; a request still on its way has none yet ("?"). */
    void setPerson(const QString& name, const QString& initials, Air::LinkState state);
    /**
     * While a request waits and its panel is open, the dashes run from you towards the other end
     * for a few seconds, then stand: nothing in the product moves without end. They stand at once
     * with less motion and once the link is there.
     */
    void setRunning(bool running);

protected:
    void paintEvent(QPaintEvent* event) override;
    void hideEvent(QHideEvent* event) override;

private:
    QString name;
    QString initials;
    Air::LinkState state = Air::LinkState::None;
    /** How far the dashes have run, in pixels along the line. */
    qreal phase = 0;
    QPointer<QVariantAnimation> run;
};

/** kq-switch: 40 by 24 px; the knob slides over 200 ms, or jumps with less motion. */
class AirSwitch : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirSwitch(QWidget* parent = nullptr);

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void checkStateSet() override;
    void nextCheckState() override;

private:
    void slide();

    qreal position = 0;
    /** A click is turning the switch: it slides instead of jumping. */
    bool toggling = false;
    QPointer<QVariantAnimation> motion;
};

/**
 * The conversation column while no conversation is open: one sentence in the middle on what to do,
 * choose a conversation in the list or paste an ID into the command line.
 */
class AirNoChat : public QWidget
{
    Q_OBJECT
public:
    explicit AirNoChat(QWidget* parent = nullptr);
    ~AirNoChat() override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    void retranslate();
    void restyle();

    QLabel* sentence = nullptr;
};
