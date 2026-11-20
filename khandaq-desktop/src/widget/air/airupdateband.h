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

class AirButton;
class AirIconButton;
class QLabel;
class QVariantAnimation;
class UpdateCheck;
class UpdateInstaller;

/**
 * KHANDAQ (0.2.65): the band of a new version, under the band of no network. It comes when the update
 * check finds a newer release for this copy: "Khandaq 0.2.65 is available", What's new (the release
 * notes in the language of the interface), Update, and a cross that hides it until Khandaq starts
 * again or Check now in About finds the version once more. Update downloads and checks the file and
 * puts it in place as this copy allows (UpdateInstaller); the band says how far the download is and
 * what went wrong, and when the file is left to the person, where it is and how to install it.
 */
class AirUpdateBand : public QWidget
{
    Q_OBJECT
public:
    AirUpdateBand(UpdateCheck& check, UpdateInstaller& installer, QWidget* parent = nullptr);
    ~AirUpdateBand() override;

    /** Shows the band again after the cross, while there is a version to offer. */
    void reopen();

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;

private:
    /** Shows, hides and words the band as the check and the download stand. */
    void follow();
    void dismiss();
    void openNotes();
    void revealFile();
    void slide(bool in);
    void place();
    int contentHeight() const;
    void fade(qreal opacity);
    void restyle();
    void retranslate();

    UpdateCheck& check;
    UpdateInstaller& installer;
    bool dismissed = false;
    /** The verified download shown in the file manager already. */
    QString revealed;
    QWidget* content = nullptr;
    QLabel* glyph = nullptr;
    QLabel* title = nullptr;
    QLabel* note = nullptr;
    AirButton* notesButton = nullptr;
    AirButton* revealButton = nullptr;
    AirButton* installButton = nullptr;
    AirIconButton* hideButton = nullptr;
    QVariantAnimation* motion = nullptr;
    qreal shown = 0;
    bool fading = false;
    bool goingIn = false;
};
