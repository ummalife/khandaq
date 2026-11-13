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
#include "src/widget/air/airpanels.h"

#include <QLocale>
#include <QPixmap>
#include <QVector>
#include <QWidget>

class AirButton;
class AirDayStrip;
class AirPathPicture;
class AirSwitch;
class AirTile;
class QBoxLayout;
class QLabel;
class QVBoxLayout;

/** A file of the conversation, for the "Files" section. */
struct AirDossierFile
{
    QString name;
    /** Where the file is on this computer; empty while it is not here. */
    QString path;
    qint64 size = 0;
};

/** Everything the dossier says about one person. */
struct AirDossierInfo
{
    QString name;
    QString statusMessage;
    int tone = 0;
    QPixmap photo;
    /** The 64 hexadecimal characters of the public key. */
    QString publicKey;
    Air::LinkState link = Air::LinkState::None;
    Air::DayStrip strip;
    bool liveNow = false;
    bool hasBest = false;
    int bestFrom = 0;
    int bestTo = 0;
    bool muted = false;
    /** The date the key was verified on, as the interface writes dates; empty while it is not. */
    QString verifiedDate;
    QVector<AirDossierFile> files;
    /** The language of the interface, for sizes and what a screen reader says. */
    QLocale locale;
};

/**
 * The dossier of a person (screen 10): a fold panel over the conversation column with its sections
 * in two columns.
 */
class AirDossier : public AirFoldPanel
{
    Q_OBJECT
public:
    explicit AirDossier(QWidget* parent);
    ~AirDossier() override;

    void setInfo(const AirDossierInfo& info);

signals:
    void renameRequested();
    void muteChanged(bool muted);
    void verifiedChanged(bool verified);
    /** Remove was confirmed: the contact and the conversation go from this device. */
    void removeRequested();
    /** Block was confirmed: the key goes on the block list, then the contact is removed. */
    void blockRequested();
    /** A file of the "Files" section was clicked; it is opened as from the file list. */
    void fileOpenRequested(const QString& path);

protected:
    void arrange(int width) override;
    void aboutToFold() override;

private:
    QVBoxLayout* section(QBoxLayout* column, QLabel** title);
    void retranslate();
    void restyle();
    void showQr();
    void refreshFiles();

    AirDossierInfo info;
    QBoxLayout* columns = nullptr;

    AirTile* tile = nullptr;
    QLabel* nameLabel = nullptr;
    QLabel* statusLabel = nullptr;
    AirButton* renameButton = nullptr;

    QLabel* linkTitle = nullptr;
    AirPathPicture* path = nullptr;
    QLabel* linkText = nullptr;

    QLabel* dayTitle = nullptr;
    AirDayStrip* strip = nullptr;
    QWidget* axis = nullptr;
    QLabel* bestText = nullptr;
    QLabel* dayNote = nullptr;

    QLabel* notifyTitle = nullptr;
    QLabel* noWake = nullptr;
    QLabel* muteLabel = nullptr;
    AirSwitch* muteSwitch = nullptr;
    QLabel* mutedNote = nullptr;

    QLabel* verifyTitle = nullptr;
    QLabel* verifyText = nullptr;
    AirButton* verifyButton = nullptr;
    QWidget* verifyBox = nullptr;
    QLabel* verifyMessage = nullptr;
    QLabel* verifyKey = nullptr;
    AirButton* matchButton = nullptr;

    QLabel* keyTitle = nullptr;
    QLabel* keyText = nullptr;
    AirButton* copyButton = nullptr;
    AirButton* qrButton = nullptr;

    QWidget* filesSection = nullptr;
    QLabel* filesTitle = nullptr;
    QVBoxLayout* filesList = nullptr;

    AirButton* blockButton = nullptr;
    AirButton* removeButton = nullptr;
};
