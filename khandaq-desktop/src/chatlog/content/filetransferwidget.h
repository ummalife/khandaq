/*
    Copyright © 2014-2019 by The qTox Project Contributors
    Copyright © 2026 by The Khandaq Project

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

#include <QElapsedTimer>
#include <QFutureWatcher>
#include <QImage>
#include <QVector>
#include <QWidget>

#include "src/core/toxfile.h"
#include "src/model/air/airconversation.h"
#include "src/model/air/airmedia.h"

class AirButton;
class AirProgressBar;
class AirVoiceNote;
class CoreFile;
class IMessageBoxManager;
class QHBoxLayout;
class QLabel;
class Settings;
class Style;

namespace FileEntryParts {
class Name;
class Preview;
} // namespace FileEntryParts

/**
 * A file, a photo or a voice note as an entry of the transcript (E8): a photo shows inline in a box
 * that has its size before the picture loads; a voice note is a player; any other file is its glyph,
 * name and size. Under them, while the file is not here, the bar with what is left, one line of state
 * in words and the actions as quiet buttons. The words and the actions follow Air::fileEntryState.
 *
 * The entry tells its height for a width (heightForWidth) and says when it changed, so the line of
 * the transcript around it lays out again.
 */
class FileTransferWidget final : public QWidget
{
    Q_OBJECT

public:
    FileTransferWidget(QWidget* parent, CoreFile& _coreFile, ToxFile file, Settings& settings,
                       Style& style, IMessageBoxManager& messageBoxManager);
    ~FileTransferWidget() override;
    bool isActive() const;
    void onFileTransferUpdate(ToxFile file);

    /**
     * Where the entry stands: a group file travels whole and has nothing to answer; personName is
     * the short name of the other person of a conversation, for "Waits for Yusuf to accept it".
     */
    void setConversation(bool group, const QString& personName, bool personOnline);

    /** The mark of a file of ours, for the line around the entry. */
    Air::EntryStatus status() const;

    bool hasHeightForWidth() const override;
    int heightForWidth(int width) const override;
    QSize sizeHint() const override;

signals:
    /** The entry grew or shrank: a bar, a note or a preview came or went. */
    void heightChanged();

private:
    void restyle();
    void retranslate();
    /** Shows the state of the transfer in fileInfo; the parts that change size say so. */
    void refresh();
    void setActions(const QVector<Air::FileAction>& actions);
    void act(Air::FileAction action);
    void accept();
    /** Asks where to save the file, then accepts it there. */
    void acceptAsked();
    /** Accepts a voice note into the app's folder of voice notes; play: it plays once it is here. */
    void acceptVoiceNote(bool play);
    void sendAgain();
    void open();
    void showInFolder();
    void loadPreview();
    void onPreviewLoaded();
    Air::FileFacts facts() const;
    QString sizeText() const;
    void tellHeight();

    CoreFile& coreFile;
    ToxFile fileInfo;
    Settings& settings;
    Style& style;
    IMessageBoxManager& messageBoxManager;

    bool active = true;
    bool group = false;
    QString personName;
    bool personOnline = true;
    /** Send again was pressed once: the new transfer is an entry of its own. */
    bool resent = false;
    QElapsedTimer lastProgress;
    int lastHeight = -1;

    FileEntryParts::Preview* preview = nullptr;
    AirVoiceNote* voice = nullptr;
    QWidget* head = nullptr;
    QLabel* icon = nullptr;
    FileEntryParts::Name* name = nullptr;
    QLabel* size = nullptr;
    AirProgressBar* bar = nullptr;
    QLabel* note = nullptr;
    QWidget* actionsRow = nullptr;
    QHBoxLayout* actionsLayout = nullptr;
    QVector<Air::FileAction> shownActions;
    QVector<AirButton*> actionButtons;

    /** The photo shown, once its picture is read off the main thread. */
    QString previewPath;
    QFutureWatcher<QImage> previewLoad;
    qint64 voiceLengthMs = -1;
    QString voicePath;
    /** Listen was pressed for the voice note before it was here: it plays when it comes. */
    bool playWhenHere = false;
};
