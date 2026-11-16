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

#include "src/model/air/airnetwork.h"

#include <QElapsedTimer>
#include <QTimer>
#include <QWidget>

class AirSource;
class QLabel;
class QVariantAnimation;

/**
 * KHANDAQ (0.2.57): the band of no network, across the top of the window's content under the Air
 * band. It comes once the computer has had no network for 3 s, says Connecting while the node gets
 * online again and Back online for a moment, then slides away (Air::NoNetworkNotice). It pushes the
 * list and the conversation down and covers nothing; it makes no sound, and a screen reader hears
 * its title once, as it comes. The line under the name says what it always said.
 */
class AirNetworkBand : public QWidget
{
    Q_OBJECT
public:
    explicit AirNetworkBand(AirSource& source, QWidget* parent = nullptr);
    ~AirNetworkBand() override;

    Air::NoNetworkNotice::Phase phase() const;

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;

private:
    /** Asks the system whether the computer has a network, then follows. */
    void askSystem();
    /** Feeds the notice what is known now and shows the phase it is in. */
    void follow();
    void present(Air::NoNetworkNotice::Phase phase);
    void slide(bool in);
    /** The band as tall as its part of the way in, its content at its foot. */
    void place();
    int contentHeight() const;
    void fade(qreal opacity);
    void restyle();
    void retranslate();

    AirSource& source;
    Air::NoNetworkNotice notice;
    Air::NoNetworkNotice::Phase shownPhase = Air::NoNetworkNotice::Phase::Hidden;
    bool network = true;
    QElapsedTimer clock;
    QTimer poll;
    QTimer step;
    QWidget* content = nullptr;
    QLabel* glyph = nullptr;
    QLabel* title = nullptr;
    QLabel* note = nullptr;
    QVariantAnimation* motion = nullptr;
    /** How far the band is in, from 0 to 1. */
    qreal shown = 0;
    /** The running motion is a fade (reduced motion) rather than a slide. */
    bool fading = false;
    bool goingIn = false;
};
