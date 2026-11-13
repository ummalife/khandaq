/*
    Copyright © 2014-2019 by The qTox Project Contributors

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
#include <QLabel>
#include <QLocale>
#include <QPointer>
#include <QSet>
#include <QTimer>

#include <functional>

#include "genericchatform.h"
#include "src/core/core.h"
#include "src/model/ichatlog.h"
#include "src/model/imessagedispatcher.h"
#include "src/model/status.h"
#include "src/persistence/history.h"
#include "src/widget/tool/screenshotgrabber.h"
#include "src/video/netcamview.h"

class AirCallBand;
class AirDossier;
class AirFailedList;
class AirLocal;
class AirSource;
class AirTray;
class ChatHistory;
class FileTransferInstance;
struct AirDossierInfo;
class Friend;
class History;
class OfflineMsgEngine;
class QPixmap;
class QHideEvent;
class QMoveEvent;
class ImagePreviewButton;
class DocumentCache;
class SmileyPack;
class Settings;
class Style;
class Profile;
class IMessageBoxManager;
class ContentDialogManager;
class FriendList;
class GroupList;

class ChatForm : public GenericChatForm
{
    Q_OBJECT
public:
    ChatForm(Profile& profile, Friend* chatFriend, IChatLog& chatLog_,
        IMessageDispatcher& messageDispatcher_, DocumentCache& documentCache, SmileyPack& smileyPack,
        CameraSource& cameraSource, Settings& settings, Style& style, IMessageBoxManager& messageBoxManager,
        ContentDialogManager& contentDialogManager, FriendList& friendList, GroupList& groupList);
    ~ChatForm() override;
    void setStatusMessage(const QString& newMessage);

    void setFriendTyping(bool isTyping_);

    /**
     * Where the conversation learns how far the person is (the list's view of the network) and
     * what this device remembers about them (online hours, mute, verification).
     */
    void setAir(AirSource* source, AirLocal* local);

    void show(ContentLayout* contentLayout_) final;

    static const QString ACTION_PREFIX;

signals:

    void incomingNotification(uint32_t friendId);
    void outgoingNotification();
    void stopNotification();
    void endCallNotification();
    void rejectCall(uint32_t friendId);
    void acceptCall(uint32_t friendId);
    void updateFriendActivity(Friend& frnd);
    /** "Remove" in the dossier, confirmed there: the main window removes the contact. */
    void removeFriendRequested(const ToxPk& friendPk);
    void blockFriendRequested(const ToxPk& friendPk);

public slots:
    void onAvInvite(uint32_t friendId, bool video);
    void onAvStart(uint32_t friendId, bool video);
    void onAvEnd(uint32_t friendId, bool error);
    void onAvatarChanged(const ToxPk& friendPk, const QPixmap& pic);
    void onExtensionSupportChanged(ExtensionSet extensions);
    void clearChatArea();
    void onShowMessagesClicked();
    void onSplitterMoved(int pos, int index);
    void reloadTheme() final;

private slots:
    void updateFriendActivityForFile(const ToxFile& file);
    void onAttachClicked() override;
    void onPasteFiles(const QStringList& paths) override;
    void onScreenshotClicked() override;

    void onTextEditChanged();
    void onCallTriggered();
    void onVideoCallTriggered();
    void onRejectCallTriggered();
    void onMicMuteToggle();
    void onVolMuteToggle();

    void onFriendStatusChanged(const ToxPk& friendPk, Status::Status status);
    void onFriendTypingChanged(quint32 friendId, bool isTyping_);
    void onFriendNameChanged(const QString& name);
    void onStatusMessage(const QString& message);
    void onUpdateTime();
    void sendLocalFiles(const QStringList& paths);
    void previewImage(const QPixmap& pixmap);
    void cancelImagePreview();
    void sendImageFromPreview();
    void doScreenshot();

    void callUpdateFriendActivity();

private:
    void updateMuteMicButton();
    void updateMuteVolButton();
    void retranslateUi();
    void startCounter();
    void stopCounter();
    void updateCallButtons();

    // A call (E9): access is asked first, someone offline can be waited for, every failure is said.
    /** A call or a video call from the head: asks for access, then calls or offers to wait. */
    void requestCall(bool video);
    /** Calls now; a refusal of toxav is said in a dialog. */
    void placeCall(bool video);
    /** "Yusuf is offline: the call will not get through", with Wait and call. */
    void askToWait(bool video);
    void cancelWaiting();
    /** Answer on the band: the microphone first, the camera for With video, then the answer. */
    void answerIncoming(bool video);
    /** Their call still rings: they may hang up while the system asks for access. */
    bool stillRinging() const;
    void finishAnswer(bool video);
    /** Asks for the microphone, and the camera for video; proceed gets what may be used. */
    void checkCallAccess(bool video, std::function<void(bool video)> proceed);
    void explainMicrophone();
    void explainCamera(std::function<void(bool video)> proceed);
    /** One entry of the transcript per call, at its end. */
    void recordCall(bool answered, bool error);
    void showNetcam();
    void hideNetcam();

    void refreshHead();
    void refreshQueue();
    /** Tab goes down the column as it shows: messages, unsent rows, the tray, then the field. */
    void orderTabs();
    void refreshCounter();
    void refreshPhoto();
    void toggleDossier();
    AirDossierInfo dossierInfo() const;
    QLocale interfaceLocale() const;
    void editQueued(qint64 id);
    void cancelQueued(qint64 id);
    void retryQueued(qint64 id);
    bool editLastQueued();

protected:
    std::unique_ptr<NetCamView> createNetcam();
    void dragEnterEvent(QDragEnterEvent* ev) final;
    void dropEvent(QDropEvent* ev) final;
    void hideEvent(QHideEvent* event) final;
    void showEvent(QShowEvent* event) final;

private:
    Core& core;
    Friend* f;
    AirCallBand* callBand;
    /** Wait and call was chosen: the call starts once the person is online. */
    bool waitingToCall = false;
    bool waitingVideo = false;
    /** The call that rings or is on came from them. */
    bool callIncoming = false;
    QTimer* callDurationTimer;
    QTimer typingTimer;
    QElapsedTimer timeElapsed;
    QPixmap imagePreviewSource;
    ImagePreviewButton* imagePreview;
    bool isTyping;
    bool friendTyping = false;
    bool lastCallIsVideo;
    /** The queue of this conversation; null only where the log is not a ChatHistory. */
    ChatHistory* queueLog = nullptr;
    QPointer<AirSource> airSource;
    QPointer<AirLocal> airLocal;
    AirFailedList* failedList;
    AirTray* tray;
    AirDossier* dossier;
    /** "offline for 25 minutes" ages while nothing happens; the head reads it again now and then. */
    QTimer headTicker;
    /** Many changes come in bursts; the head is read once after them. */
    QTimer headLater;
    QPixmap photo;
    std::unique_ptr<NetCamView> netcam;
    CameraSource& cameraSource;
    Settings& settings;
    Style& style;
    ContentDialogManager& contentDialogManager;
    Profile& profile;
};
