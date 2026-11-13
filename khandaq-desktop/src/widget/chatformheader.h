/*
    Copyright © 2017-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#pragma once

#include <QPointer>
#include <QWidget>

#include "src/core/extension.h"
#include "src/model/air/airconversation.h"
#include "src/widget/air/airchatparts.h"

#include <memory>

class QVBoxLayout;
class QHBoxLayout;
class CroppingLabel;
class QPushButton;
class QLabel;
class QVariantAnimation;
class AirIconButton;
class Settings;
class Style;

namespace ChatFormHeaderParts {
class Title;
}

/**
 * The head of a conversation (screen 5): the tile and the name, the link mark with the presence
 * line under it ("online since 9:40, direct link") and the day strip of the last 24 hours, then
 * search, video and call. Clicking the name unfolds the dossier; its chevron turns with it.
 */
class ChatFormHeader : public QWidget
{
    Q_OBJECT
public:
    enum class CallButtonState {
        Disabled = 0,    // Grey
        Avaliable = 1,   // Green
        InCall = 2,      // Red
        Outgoing = 3,    // Yellow
        Incoming = 4,    // Yellow
    };
    enum class ToolButtonState {
        Disabled = 0,    // Grey
        Off = 1,         // Green
        On = 2,          // Red
    };
    enum Mode {
        None = 0,
        Audio = 1,
        Video = 2,
        AV = Audio | Video
    };

    ChatFormHeader(Settings& settings, Style& style, QWidget* parent = nullptr);
    ~ChatFormHeader();

    void setName(const QString& newName);
    void setMode(Mode mode_);

    void updateExtensionSupport(ExtensionSet extensions);
    /**
     * The call buttons stay on while the person is offline: a click offers to wait for them
     * (Wait and call), so online only matters to the caller.
     */
    void updateCallButtons(bool online, bool audio, bool video = false);
    /** A call band is up under the name: it holds the call's own buttons, so these step aside. */
    void setCallsBusy(bool busy);
    /**
     * Where the call buttons of a conversation stand, a group says that it has no calls: the phone
     * glyph and the words, with the reason in the tooltip. It is not a button.
     */
    void setNoCallsNote(bool shown);
    void updateMuteMicButton(bool active, bool inputMuted);
    void updateMuteVolButton(bool active, bool outputMuted);

    /** A photo for the tile; a null pixmap goes back to the initials. */
    void setAvatar(const QPixmap& img);
    QSize getAvatarSize() const;

    void setTile(const QString& title, int tone, AirTile::Kind kind);
    /** The quiet line under the name; an empty text hides it with the link mark. */
    void setPresence(Air::LinkState link, const QString& text);
    /** The same line without a link mark: a group's members and how many are online. */
    void setSubtitle(const QString& text);
    /** A band under the head that lines up with the name, as the day band does. */
    void addBand(QWidget* band);
    /** The compact day strip and the words beside it. */
    void setDayBand(const Air::DayStrip& strip, bool liveNow, const QString& hint);
    void setDayBandVisible(bool visible);
    /** Whether the name unfolds a dossier. */
    void setDossierEnabled(bool enabled);
    /** Whether a click on the name renames the chat in place, as it does for a group. */
    void setNameEditable(bool editable);
    /** The title opens a group's dossier rather than a person's: its tooltip says so. */
    void setGroupDossier(bool group);
    /** Turns the chevron: open points it up. */
    void setDossierOpen(bool open);
    /** Starts renaming in place, as the dossier's "Rename" asks. */
    void beginRename();
    void setSearchVisible(bool visible);

    // TODO: Remove
    void addWidget(QWidget* widget, int stretch = 0, Qt::Alignment alignment = Qt::Alignment());
    void addLayout(QLayout* layout);
    void addStretch();

public slots:
    void reloadTheme();

signals:
    void searchRequested();
    void dossierToggled();
    void callTriggered();
    void videoCallTriggered();
    void micMuteToggle();
    void volMuteToggle();

    void nameChanged(const QString& name);

protected:
    void paintEvent(QPaintEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private slots:
    void retranslateUi();
    void updateButtonsView();

private:
    void restyle();
    void refreshCallButtons();

    Mode mode;
    AirTile* tile;
    ChatFormHeaderParts::Title* title;
    QVBoxLayout* headTextLayout;
    CroppingLabel* nameLabel;
    AirLinkMark* presenceMark;
    QLabel* presenceLabel;
    QWidget* dayBand;
    AirDayStrip* dayStrip;
    QLabel* dayHint;
    bool dossierEnabled = false;
    bool dossierOpen = false;
    bool groupDossier = false;
    QVBoxLayout* column = nullptr;

    AirIconButton* searchButton;
    AirIconButton* callButton;
    AirIconButton* videoButton;
    QPushButton* volButton;
    QPushButton* micButton;
    QWidget* noCallsNote = nullptr;
    QLabel* noCallsGlyph = nullptr;
    QLabel* noCallsWords = nullptr;
    bool callsBusy = false;

    CallButtonState callState;
    CallButtonState videoState;
    ToolButtonState volState;
    ToolButtonState micState;

    Settings& settings;
    Style& style;
};
