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

#include <QStringList>

class QWidget;

/**
 * What a screen reader finds in the two views that paint their lines themselves: the chat list
 * (AirListView) and a transcript (ChatWidget). Without it VoiceOver, Narrator and Orca met one
 * unnamed picture in each. With it every heading, card, row and entry is an element of its own and
 * says what the phones say (iOS AirRowCell and AirTranscriptCell): a row its name, reach, preview
 * and counts; an entry who wrote it, when, its words and its state. A card has its two buttons, a
 * row opens with the press action.
 */
namespace AirAccessible {

/** Installs the interfaces; every call after the first does nothing. */
void install();

/**
 * Everything a screen reader is offered under widget, depth first, one line per element with a
 * name: its role, a tab, then the name. The walk and the tests read the tree through it.
 */
QStringList spokenTree(QWidget* widget);

} // namespace AirAccessible
