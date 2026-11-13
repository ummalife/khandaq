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

#include <QWidget>

class AirCommandField;
class AirIconButton;
class AirPill;
class AirSelfButton;
class QLineEdit;

/**
 * The "Air" band across the top of the window (design/redesign/02-plan.md, screen 1, desktop): you
 * and your node, the command line, the filter pills and the actions. It holds no state of its own
 * beyond what it shows; AirController feeds it and listens to it.
 */
class AirBand : public QWidget
{
    Q_OBJECT
public:
    explicit AirBand(QWidget* parent = nullptr);
    ~AirBand() override;

    void setSelf(const QString& name, const QPixmap& avatar);
    /** The line under your name: the state of the node in words. */
    void setNetwork(Air::NetworkState state);
    void setCounts(const Air::Counts& counts);
    void setFilter(Air::Filter filter);

    QLineEdit* commandLine() const;
    void focusCommandLine();

    QSize minimumSizeHint() const override;

signals:
    void profileRequested();
    /** The line under your name was pressed: it opens the state of the network. */
    void networkRequested();
    void statusMenuRequested(const QPoint& globalPos);
    void queryChanged(const QString& query);
    /**
     * A whole Tox ID landed in the command line, or Enter was pressed on the start of one: it opens
     * New contact with those characters instead of a search.
     */
    void contactAddressEntered(const QString& address);
    /**
     * The command line holds the start of an ID (Air::commandIdCandidate) as its upper-case hex, or
     * an empty string once it holds anything else.
     */
    void idStartChanged(const QString& hex);
    void filterToggled(Air::Filter filter);
    void newContactRequested();
    void groupsRequested();
    void transfersRequested();
    void settingsRequested();
    /** Up and down in the command line walk the list; Enter opens the row under the cursor. */
    void navigateRequested(int step);
    void activateRequested();

protected:
    void paintEvent(QPaintEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void retranslate();
    void onCommandEdited(const QString& text);

    AirSelfButton* self = nullptr;
    AirIconButton* statusButton = nullptr;
    AirCommandField* command = nullptr;
    AirPill* requestsPill = nullptr;
    AirPill* transitPill = nullptr;
    AirIconButton* newContactButton = nullptr;
    AirIconButton* groupsButton = nullptr;
    AirIconButton* transfersButton = nullptr;
    AirIconButton* favoritesButton = nullptr;
    AirIconButton* settingsButton = nullptr;
    Air::NetworkState network = Air::NetworkState::Connecting;
};
