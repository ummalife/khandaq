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

#include <QAbstractButton>
#include <QImage>
#include <QWidget>

class AirLinkMark;

/**
 * KHANDAQ (calls stage, 26.11): words over video with the shadow of the call screens, fixed-black
 * at 50 %, blurred 4 px and 1 px down, so they stay legible on a bright picture without a gradient
 * band. The blurred shadow is kept until the words, the font or the size change, since the video
 * under it repaints every frame.
 */
class AirShadowedText
{
public:
    /** How far the shadow reaches out of the rect of the words. */
    static int reach();

    void paint(QPainter& painter, const QRect& rect, int flags, const QString& text,
               const QColor& color);

private:
    QImage shadow;
    QString key;
};

/**
 * KHANDAQ (calls stage, 26.11): a control of the video call stage, drawn as the phones draw theirs
 * (iOS CallButton and AirCallControl): a 64 px circle with its word on one line under it, in a
 * column of the control row. A switch is translucent over the video and turns white with a dark
 * glyph while it is engaged; End is filled red. The glyph says the state (a crossed microphone
 * while it is muted), and a screen reader hears the switch as checked while what it turns is on.
 * The word shrinks to fit its column, down to 11 px, instead of wrapping.
 */
class AirCallControl : public QAbstractButton
{
    Q_OBJECT
public:
    enum class Kind
    {
        /** Engaged while our microphone is muted. */
        Microphone,
        /** Engaged while the other person's voice is muted. */
        Sound,
        /** Engaged while the box with our own video is hidden. */
        SelfView,
        /** Engaged while the conversation under the video is hidden. */
        Messages,
        /** Engaged while the call fills the screen. */
        FullScreen,
        /** Ends the call; never engaged. */
        End,
    };

    explicit AirCallControl(Kind kind, QWidget* parent = nullptr);
    ~AirCallControl() override;

    Kind kind() const;
    void setEngaged(bool engaged);
    bool isEngaged() const;
    /** A dot on the circle: messages came while the conversation was hidden. */
    void setNotice(bool notice);
    bool hasNotice() const;

    /** The height of a control: the circle, the gap and the line of its word. */
    static int controlHeight();
    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    bool hitButton(const QPoint& pos) const override;
    void nextCheckState() override;

private:
    void retranslate();
    QRectF circleRect() const;
    QRect wordRect() const;

    const Kind controlKind;
    bool engaged = false;
    bool notice = false;
    AirShadowedText word;
};

/**
 * KHANDAQ (calls stage, 26.11): the head of the video call stage in full screen, where the head of
 * the conversation is out of sight: the person's name in the display size, the time of the call in
 * tabular figures and, centred under it, the link chip (the link mark and its words on a pill of
 * the dark scrim). The name and the time carry the shadow of the call screens.
 */
class AirCallHeader : public QWidget
{
    Q_OBJECT
public:
    explicit AirCallHeader(QWidget* parent = nullptr);
    ~AirCallHeader() override;

    void setName(const QString& name);
    void setSeconds(qint64 seconds);
    void setLink(Air::LinkState link);

    /** The height of the name, the time and the chip, without the room of the shadow. */
    static int contentHeight();
    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void changeEvent(QEvent* event) override;

private:
    void retranslate();
    QRect chipRect() const;
    void placeMark();

    QString name;
    qint64 seconds = 0;
    Air::LinkState link = Air::LinkState::None;
    QString linkWords;
    AirLinkMark* mark = nullptr;
    AirShadowedText nameText;
    AirShadowedText timeText;
};
