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

#include "src/widget/air/airtokens.h"

#include <QColor>
#include <QEasingCurve>
#include <QFont>
#include <QHash>
#include <QObject>
#include <QPixmap>
#include <QPointer>

#include <functional>

class QTimer;
class QWidget;
class Settings;
class Style;

/**
 * The redesign's look at run time: the token colours of the current theme, the Plex type scale, the
 * line icons and the motion rules (design/system/tokens.json through tools/gen-design-tokens.py).
 *
 * One instance for the app. It applies the theme mode from the settings (follow the system, light
 * or dark), keeps following the system while asked to, gives the whole application a palette made
 * of tokens, and emits changed() so painted widgets repaint.
 */
class AirStyle : public QObject
{
    Q_OBJECT
public:
    static AirStyle& instance();

    /**
     * Registers the bundled IBM Plex faces and makes Plex Sans, at the body size, the application
     * font. Call once, right after the QApplication exists and before any widget or font is made.
     */
    static void installFonts();
    /**
     * Makes each Plex text family fall back on the other for the glyphs it lacks (Arabic in Plex
     * Sans, Latin and Cyrillic in Plex Sans Arabic) through the font substitution table. Qt 5.13
     * and later get the same fallback from the family list font() gives each font; installFonts()
     * calls this on Qt 5.12, which has no such list. Calling it again changes nothing.
     */
    static void substituteTextFamilies();

    bool isDark() const;
    /** The system asks for less motion: only short fades, nothing slides. */
    bool reducedMotion() const;

    QColor color(AirTokens::Color role) const;
    /** One of the six initials tones; the index wraps. */
    QColor tone(int index) const;
    /** A role of the type scale, in Plex Sans (Plex Sans Arabic first in right-to-left languages). */
    QFont font(AirTokens::TextRole role) const;
    int lineHeight(AirTokens::TextRole role) const;
    /** A line icon of the set, stroked in color, size by size device-independent pixels. */
    QPixmap icon(const QString& name, const QColor& color, int size, qreal devicePixelRatio) const;

    /** A duration from the motion scale, capped when the system asks for less motion. */
    int duration(int milliseconds) const;
    QEasingCurve easeOut() const;
    QEasingCurve easeStandard() const;

    /**
     * Applies the theme mode from the settings and keeps it applied: when the setting changes and,
     * while it follows the system, when the system appearance changes. window is cross-faded
     * between the two themes.
     */
    void follow(Settings& settings, Style& style, QWidget* window);

    /**
     * Asks every Air widget to restyle and repaint. The legacy theme code clears the style sheets
     * of the whole window when it reloads (Widget::reloadTheme), and calls this afterwards.
     */
    void refresh();

    /**
     * Runs change under a cross-fade of the window, as a new theme is put on: a new language
     * rebuilds the interface so (DESIGN.md, "Settings on the phone"). Without a window on screen
     * change just runs.
     */
    void changeUnderCrossFade(const std::function<void()>& change);

signals:
    void changed();

private:
    AirStyle();
    void apply(bool animate);
    void applyApplicationPalette();
    void crossFade(const QPixmap& before);
    void pollSystem();

    bool dark = false;
    bool reduced = false;
    bool applied = false;
    Settings* settings = nullptr;
    Style* style = nullptr;
    QPointer<QWidget> window;
    QTimer* pollTimer = nullptr;
    mutable QHash<QString, QPixmap> iconCache;
};
